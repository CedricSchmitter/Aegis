package dev.aegis.documents.adapters.persistence;

import dev.aegis.documents.application.api.PassageQueries;
import dev.aegis.documents.application.api.PassageQueries.*;
import dev.aegis.documents.application.ports.PassageIndex;
import dev.aegis.documents.domain.text.PassageRevision;
import dev.aegis.shared.*;
import java.nio.charset.StandardCharsets;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Transactional queue/outbox and atomic revision publication; archive I/O never occurs here. */
@Component
public class JdbcPassageIndex implements PassageIndex {
    public static final Duration FRESHNESS = Duration.ofHours(24);
    private static final String ACTIVE = "d.state='ARCHIVED' AND d.paperless_document_id IS NOT NULL AND d.trashed_at IS NULL AND d.purge_requested_at IS NULL";
    private static final String CURRENT = ACTIVE + " AND i.state='CURRENT' AND i.archive_id=d.paperless_document_id AND i.original_checksum=d.checksum_sha256 AND i.build_epoch=c.build_epoch AND i.verified_at >= :cutoff";
    private static final String STATE = """
            CASE WHEN i.document_id IS NULL THEN 'PENDING'
                 WHEN i.archive_id<>d.paperless_document_id OR i.original_checksum<>d.checksum_sha256
                      OR i.build_epoch<>c.build_epoch THEN 'STALE'
                 WHEN i.state='CURRENT' AND i.verified_at < :cutoff THEN 'STALE'
                 ELSE i.state END""";
    private final JdbcClient jdbc;
    private final JdbcTemplate batch;
    private final TransactionTemplate tx;
    public JdbcPassageIndex(JdbcClient jdbc, JdbcTemplate batch, PlatformTransactionManager manager) {
        this.jdbc = jdbc; this.batch = batch; this.tx = new TransactionTemplate(manager);
    }
    private static Timestamp at(Instant now) { return Timestamp.from(now); }
    private void changed() { jdbc.sql("UPDATE document_passage_control SET data_epoch=data_epoch+1").update(); }

    @Override public void request(DocumentId id, Instant now) {
        // Joins the surrounding archival transaction; this row is the durable work publication.
        jdbc.sql("""
                INSERT INTO document_passage_index (document_id,archive_id,original_checksum,build_epoch,next_attempt_at)
                SELECT d.id,d.paperless_document_id,d.checksum_sha256,c.build_epoch,:now
                  FROM documents d CROSS JOIN document_passage_control c WHERE d.id=:id AND """ + ACTIVE + """
                ON CONFLICT (document_id) DO UPDATE SET archive_id=EXCLUDED.archive_id,
                    original_checksum=EXCLUDED.original_checksum, generation=document_passage_index.generation+1,
                    build_epoch=EXCLUDED.build_epoch, state='PENDING', attempts=0, next_attempt_at=EXCLUDED.next_attempt_at,
                    lease=NULL,lease_until=NULL,verified_at=NULL,failure_code=NULL
                WHERE document_passage_index.archive_id<>EXCLUDED.archive_id
                   OR document_passage_index.original_checksum<>EXCLUDED.original_checksum
                """).param("id",id.value()).param("now",at(now)).update();
    }
    @Override public void invalidate(DocumentId id, Instant now) {
        tx.executeWithoutResult(s -> {
            var active = jdbc.sql("SELECT d.id FROM documents d WHERE d.id=:id AND " + ACTIVE + " FOR UPDATE")
                    .param("id",id.value()).query(UUID.class).optional();
            if (active.isEmpty()) { throw new NotFoundException("document",id.toString()); }
            request(id,now);
            jdbc.sql("""
                    UPDATE document_passage_index SET generation=generation+1,state='PENDING',attempts=0,
                        next_attempt_at=:now,lease=NULL,lease_until=NULL,verified_at=NULL,failure_code=NULL,
                        build_epoch=(SELECT build_epoch FROM document_passage_control) WHERE document_id=:id
                    """).param("id",id.value()).param("now",at(now)).update();
            changed();
        });
    }
    @Override public void suspend(DocumentId id) {
        jdbc.sql("UPDATE document_passage_index SET generation=generation+1,state='PENDING',verified_at=NULL,lease=NULL,lease_until=NULL WHERE document_id=:id")
                .param("id",id.value()).update();
        changed();
    }
    @Override public void rebuild(Instant now) {
        tx.executeWithoutResult(s -> jdbc.sql("UPDATE document_passage_control SET build_epoch=build_epoch+1,data_epoch=data_epoch+1").update());
    }
    @Override public int backfill(Instant now, int limit) {
        return tx.execute(s -> {
            int inserted = jdbc.sql("""
                    INSERT INTO document_passage_index (document_id,archive_id,original_checksum,build_epoch,next_attempt_at)
                    SELECT d.id,d.paperless_document_id,d.checksum_sha256,c.build_epoch,:now
                      FROM documents d CROSS JOIN document_passage_control c
                     WHERE """ + ACTIVE + """
                       AND NOT EXISTS (SELECT 1 FROM document_passage_index i WHERE i.document_id=d.id)
                     ORDER BY d.id LIMIT :limit ON CONFLICT DO NOTHING
                    """).param("now",at(now)).param("limit",limit).update();
            int reset = jdbc.sql("""
                    UPDATE document_passage_index i SET archive_id=d.paperless_document_id,
                        original_checksum=d.checksum_sha256,build_epoch=c.build_epoch,generation=i.generation+1,
                        state='PENDING',attempts=0,next_attempt_at=:now,lease=NULL,lease_until=NULL,
                        verified_at=NULL,failure_code=NULL
                    FROM documents d CROSS JOIN document_passage_control c
                    WHERE i.document_id=d.id AND d.id IN (
                        SELECT d.id FROM documents d JOIN document_passage_index i ON i.document_id=d.id
                        CROSS JOIN document_passage_control c WHERE """ + ACTIVE + """
                        AND (i.build_epoch<>c.build_epoch OR i.archive_id<>d.paperless_document_id
                             OR i.original_checksum<>d.checksum_sha256) ORDER BY d.id LIMIT :limit)
                    """).param("now",at(now)).param("limit",limit).update();
            jdbc.sql("""
                    UPDATE document_passage_index SET state='FAILED',failure_code='text.workerExpired',lease=NULL,
                        lease_until=NULL WHERE lease_until<=:now AND attempts>=5
                    """).param("now",at(now)).update();
            if (inserted+reset>0) { changed(); }
            return inserted+reset;
        });
    }
    @Override public Optional<Claim> claim(Instant now) {
        return tx.execute(s -> {
            var found = jdbc.sql("""
                    SELECT d.id,d.paperless_document_id,d.checksum_sha256,d.version,i.generation,i.attempts
                    FROM documents d JOIN document_passage_index i ON i.document_id=d.id
                    CROSS JOIN document_passage_control c WHERE """ + ACTIVE + """
                      AND i.build_epoch=c.build_epoch AND i.attempts<5 AND i.next_attempt_at<=:now
                      AND (i.lease IS NULL OR i.lease_until<=:now)
                    ORDER BY i.next_attempt_at,d.id LIMIT 1 FOR UPDATE OF d,i SKIP LOCKED
                    """).param("now",at(now)).query((rs,n) -> new Claim(new DocumentId(rs.getObject("id",UUID.class)),
                            rs.getInt("paperless_document_id"),rs.getString("checksum_sha256"),rs.getLong("version"),
                            rs.getLong("generation"),UUID.randomUUID(),rs.getInt("attempts")+1)).optional();
            found.ifPresent(claim -> {
                jdbc.sql("""
                        UPDATE document_passage_index SET lease=:lease,lease_until=:until,state='PENDING',
                            attempts=:attempts WHERE document_id=:id
                        """).param("lease",claim.lease()).param("until",at(now.plusSeconds(90)))
                        .param("attempts",claim.attempts()).param("id",claim.id().value()).update();
                changed();
            });
            return found;
        });
    }
    private boolean lockCurrent(Claim claim) {
        var version = jdbc.sql("SELECT d.version FROM documents d WHERE d.id=:id AND " + ACTIVE + " FOR UPDATE")
                .param("id",claim.id().value()).query(Long.class).optional();
        if (version.isEmpty()) { return false; }
        return jdbc.sql("""
                SELECT i.document_id FROM document_passage_index i JOIN documents d ON d.id=i.document_id
                CROSS JOIN document_passage_control c WHERE i.document_id=:id AND i.generation=:generation
                    AND i.lease=:lease AND i.build_epoch=c.build_epoch AND d.version=:version
                    AND d.paperless_document_id=:archive AND d.checksum_sha256=:checksum FOR UPDATE OF i
                """).param("id",claim.id().value()).param("generation",claim.generation()).param("lease",claim.lease())
                .param("version",claim.documentVersion()).param("archive",claim.archiveId()).param("checksum",claim.checksum())
                .query(UUID.class).optional().isPresent();
    }
    @Override public boolean publish(Claim claim, PassageRevision revision, Instant now) {
        return tx.execute(s -> {
            if (!lockCurrent(claim)) { return false; }
            jdbc.sql("DELETE FROM document_text_revisions WHERE document_id=:id AND revision<>:revision")
                    .param("id",claim.id().value()).param("revision",revision.revision()).update();
            int fresh = jdbc.sql("""
                    INSERT INTO document_text_revisions (document_id,revision,source_fingerprint,source_kind,
                        extraction_version,chunk_version,original_text,paged,created_at)
                    VALUES (:id,:revision,:fingerprint,:kind,:extraction,:chunk,:text,:paged,:now)
                    ON CONFLICT DO NOTHING
                    """).param("id",claim.id().value()).param("revision",revision.revision())
                    .param("fingerprint",revision.sourceFingerprint()).param("kind",revision.sourceKind())
                    .param("extraction",PassageRevision.EXTRACTION_VERSION).param("chunk",PassageRevision.CHUNK_VERSION)
                    .param("text",revision.text()).param("paged",revision.paged()).param("now",at(now)).update();
            if (fresh>0 && !revision.passages().isEmpty()) {
                batch.batchUpdate("""
                        INSERT INTO document_passages (document_id,revision,ordinal,start_position,end_position,
                            start_page,end_page,original_text,normalized_text,identifiers) VALUES (?,?,?,?,?,?,?,?,?,?)
                        """,revision.passages(),100,(ps,p) -> {
                            ps.setObject(1,claim.id().value()); ps.setString(2,revision.revision()); ps.setInt(3,p.ordinal());
                            ps.setInt(4,p.start()); ps.setInt(5,p.end()); ps.setObject(6,p.startPage()); ps.setObject(7,p.endPage());
                            ps.setString(8,p.original()); ps.setString(9,p.normalized());
                            ps.setArray(10,ps.getConnection().createArrayOf("text",p.identifiers().toArray()));
                        });
            }
            jdbc.sql("""
                    UPDATE document_passage_index SET active_revision=:revision,source_kind=:kind,verified_at=:now,
                        state=:state,attempts=0,next_attempt_at=:next,lease=NULL,lease_until=NULL,failure_code=NULL
                    WHERE document_id=:id
                    """).param("revision",revision.revision()).param("kind",revision.sourceKind()).param("now",at(now))
                    .param("state",revision.passages().isEmpty()?"NO_TEXT":"CURRENT").param("next",at(now.plus(FRESHNESS)))
                    .param("id",claim.id().value()).update();
            changed(); return true;
        });
    }
    @Override public void fail(Claim claim, String code, State state, Instant now) {
        tx.executeWithoutResult(s -> {
            if (!lockCurrent(claim)) { return; }
            jdbc.sql("""
                    UPDATE document_passage_index SET state=:state,failure_code=:code,next_attempt_at=:next,
                        lease=NULL,lease_until=NULL WHERE document_id=:id
                    """).param("state",state.name()).param("code",code)
                    .param("next",at(now.plusSeconds(Math.min(3600,60L << Math.min(claim.attempts(),5)))))
                    .param("id",claim.id().value()).update();
            changed();
        });
    }
    @Override public Coverage coverage(Instant now) {
        return jdbc.sql("""
                SELECT count(*) total,count(*) FILTER(WHERE state='CURRENT') current,
                    count(*) FILTER(WHERE state='PENDING') pending,count(*) FILTER(WHERE state='FAILED') failed,
                    count(*) FILTER(WHERE state='STALE') stale,count(*) FILTER(WHERE state='NO_TEXT') no_text,
                    count(*) FILTER(WHERE state='SOURCE_UNAVAILABLE') unavailable,min(verified_at) oldest
                FROM (SELECT """ + STATE + """
                     state,i.verified_at FROM documents d LEFT JOIN document_passage_index i ON i.document_id=d.id
                     CROSS JOIN document_passage_control c WHERE """ + ACTIVE + ") states")
                .param("cutoff",at(now.minus(FRESHNESS))).query((rs,n) -> new Coverage(rs.getLong("total"),
                        rs.getLong("current"),rs.getLong("pending"),rs.getLong("failed"),rs.getLong("stale"),
                        rs.getLong("no_text"),rs.getLong("unavailable"),instant(rs,"oldest"),now,FRESHNESS.toSeconds())).single();
    }
    @Override public Status status(DocumentId id, Instant now) {
        return jdbc.sql("SELECT d.id," + STATE + " state,i.* FROM documents d LEFT JOIN document_passage_index i ON i.document_id=d.id CROSS JOIN document_passage_control c WHERE d.id=:id AND " + ACTIVE)
                .param("id",id.value()).param("cutoff",at(now.minus(FRESHNESS)))
                .query((rs,n) -> new Status(id,State.valueOf(rs.getString("state")),rs.getString("active_revision"),
                        rs.getString("source_kind"),instant(rs,"verified_at"),rs.getInt("attempts"),instant(rs,"next_attempt_at"),
                        rs.getString("failure_code"))).optional().orElseThrow(() -> new NotFoundException("document",id.toString()));
    }
    private static Instant instant(ResultSet rs,String column) throws SQLException {
        Timestamp value=rs.getTimestamp(column); return value==null?null:value.toInstant();
    }
    private static Passage passage(ResultSet rs,int ignored) throws SQLException {
        return new Passage(new DocumentId(rs.getObject("document_id",UUID.class)),rs.getString("title"),
                rs.getString("revision"),rs.getInt("ordinal"),rs.getString("original_text"),rs.getInt("start_position"),
                rs.getInt("end_position"),rs.getObject("start_page",Integer.class),rs.getObject("end_page",Integer.class),instant(rs,"verified_at"));
    }
    private static final String PASSAGES = "SELECT p.*,d.title,i.verified_at FROM document_passages p JOIN documents d ON d.id=p.document_id JOIN document_passage_index i ON i.document_id=d.id AND i.active_revision=p.revision CROSS JOIN document_passage_control c WHERE ";
    @Override public Optional<Passage> read(DocumentId id,String revision,int ordinal,Instant now) {
        return jdbc.sql(PASSAGES+CURRENT+" AND p.document_id=:id AND p.revision=:revision AND p.ordinal=:ordinal")
                .param("cutoff",at(now.minus(FRESHNESS))).param("id",id.value()).param("revision",revision)
                .param("ordinal",ordinal).query(JdbcPassageIndex::passage).optional();
    }
    @Override public Page search(String query,String cursor,int limit,Instant now) {
        return tx.execute(s -> {
            long epoch=jdbc.sql("SELECT data_epoch FROM document_passage_control FOR SHARE").query(Long.class).single();
            String signature=PassageRevision.hash(query);
            UUID after=new UUID(0,0); int ordinal=-1;
            if (cursor!=null && !cursor.isBlank()) {
                try {
                    if (cursor.length()>400) { throw new IllegalArgumentException(); }
                    String[] parts=new String(Base64.getUrlDecoder().decode(cursor),StandardCharsets.UTF_8).split("/");
                    if (parts.length!=4 || Long.parseLong(parts[0])!=epoch || !parts[1].equals(signature)) { throw new IllegalArgumentException(); }
                    after=UUID.fromString(parts[2]); ordinal=Integer.parseInt(parts[3]);
                } catch (IllegalArgumentException invalid) { throw new InvalidInputException("passages.cursorExpired","Restart this search; its index or query changed"); }
            }
            List<Passage> found=jdbc.sql(PASSAGES+CURRENT+"""
                    AND (p.german @@ plainto_tsquery('german',:query) OR p.english @@ plainto_tsquery('english',:query)
                         OR p.literal @@ plainto_tsquery('simple',:normalized) OR p.identifiers @> ARRAY[:identifier]::text[])
                    AND (p.document_id>:after OR (p.document_id=:after AND p.ordinal>:ordinal))
                    ORDER BY p.document_id,p.ordinal LIMIT :limit
                    """).param("query",query).param("normalized",PassageRevision.normalize(query))
                    .param("identifier",PassageRevision.identifier(query)).param("cutoff",at(now.minus(FRESHNESS)))
                    .param("after",after).param("ordinal",ordinal).param("limit",limit+1).query(JdbcPassageIndex::passage).list();
            String next=null;
            if (found.size()>limit) {
                Passage last=found.get(limit-1);
                next=Base64.getUrlEncoder().withoutPadding().encodeToString((epoch+"/"+signature+"/"+last.documentId()+"/"+last.ordinal()).getBytes(StandardCharsets.UTF_8));
                found=found.subList(0,limit);
            }
            return new Page(found,next,coverage(now));
        });
    }
}
