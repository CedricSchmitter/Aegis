package dev.aegis.bootstrap;

import static org.assertj.core.api.Assertions.*;
import dev.aegis.bootstrap.support.PostgresIT;
import dev.aegis.bootstrap.support.ApiClient;
import dev.aegis.documents.application.ArchivalUseCases;
import dev.aegis.documents.application.PassageIndexWorker;
import dev.aegis.documents.application.api.*;
import dev.aegis.documents.application.ports.PassageIndex;
import dev.aegis.documents.domain.text.PassageRevision;
import dev.aegis.identity.application.api.MemberCredentialsQuery;
import dev.aegis.shared.*;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Real PostgreSQL publication, queue recovery, permissions and lexical recall; no live archive or provider. */
class PassageIndexIT extends PostgresIT {
    private static final Instant NOW=Instant.parse("2020-01-01T00:00:00Z");
    @Autowired dev.aegis.bootstrap.support.FakePaperlessArchive archive;
    @Autowired DocumentCommands documentCommands;
    @Autowired PassageIndex index;
    @Autowired PassageQueries queries;
    @Autowired PassageCommands commands;
    @Autowired PassageIndexWorker worker;
    @Autowired ArchivalUseCases archival;
    @Autowired MemberCredentialsQuery members;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager manager;
    @LocalServerPort int port;
    private final List<DocumentId> ids=new ArrayList<>();
    private Actor actor() { return new Actor(members.findCredentials(ANNA).orElseThrow().id()); }
    private DocumentId seed() {
        DocumentId id=DocumentId.random(); ids.add(id);
        jdbc.update("""
                INSERT INTO documents (id,title,original_filename,content_type,size_bytes,checksum_sha256,uploaded_by,
                    uploaded_at,staging_path,staging_present,state,attempts,paperless_document_id,version)
                VALUES (?,?,'scan.jpg','image/jpeg',100,?,?,?,'synthetic',false,'ARCHIVED',0,?,0)
                """,id.value(),"Misleading household scan",id.toString().replace("-","").repeat(2),
                actor().memberId().value(),Timestamp.from(NOW),800000+ids.size());
        index.request(id,NOW); return id;
    }
    private PassageRevision revision(String text) { return PassageRevision.of(PassageRevision.hash(text),"ARCHIVE_OCR",List.of(text),false); }
    private PassageIndex.Claim claim(DocumentId id) {
        var claim=index.claim(NOW).orElseThrow(); assertThat(claim.id()).isEqualTo(id); return claim;
    }
    @AfterEach void clean() { ids.forEach(id -> jdbc.update("DELETE FROM documents WHERE id=?",id.value())); }

    @Test void publicationIsAtomicAndOldWorkersCannotOverwriteNewGenerationOrResurrectTrashAndPurge() {
        DocumentId id=seed(); var old=claim(id);
        index.invalidate(id,NOW);
        var newer=claim(id); var current=revision("Kündigung drei Monate K-2026/004 Müller");
        assertThat(index.publish(newer,current,NOW)).isTrue();
        assertThat(index.publish(old,revision("Obsolete"),NOW)).isFalse();
        assertThat(index.search("K2026004",null,20,NOW).passages()).hasSize(1);
        assertThat(index.read(id,current.revision(),0,NOW)).isPresent();
        jdbc.update("UPDATE documents SET trashed_at=?,trashed_by=?,restorable_until=? WHERE id=?",
                Timestamp.from(NOW),actor().memberId().value(),Timestamp.from(NOW.plusSeconds(86400)),id.value());
        assertThat(index.search("Kündigung",null,20,NOW).passages()).isEmpty();
        assertThat(index.read(id,current.revision(),0,NOW)).isEmpty();
        index.suspend(id);
        jdbc.update("UPDATE documents SET trashed_at=NULL,trashed_by=NULL,restorable_until=NULL WHERE id=?",id.value());
        index.invalidate(id,NOW); var beforePurge=claim(id);
        jdbc.update("DELETE FROM documents WHERE id=?",id.value());
        assertThat(index.publish(beforePurge,current,NOW)).isFalse();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM document_passages WHERE document_id=?",Long.class,id.value())).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM document_text_revisions WHERE document_id=?",Long.class,id.value())).isZero();
    }
    @Test void rollbackCannotLeaveHalfTextOrLoseTheArchiveWorkPublication() {
        DocumentId id=seed(); var claim=claim(id);
        var transaction=new TransactionTemplate(manager);
        assertThatThrownBy(() -> transaction.executeWithoutResult(s -> {
            assertThat(index.publish(claim,revision("Candidate text"),NOW)).isTrue();
            throw new IllegalStateException("controlled rollback");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM document_text_revisions WHERE document_id=?",Long.class,id.value())).isZero();
        assertThat(index.status(id,NOW).state()).isEqualTo(PassageQueries.State.PENDING);
        assertThat(index.publish(claim,revision("Complete text"),NOW)).isTrue();
        jdbc.update("DELETE FROM document_passage_index WHERE document_id=?",id.value());
        jdbc.update("UPDATE documents SET state='SUBMITTED',paperless_document_id=NULL,submitted_at=?,paperless_task_id='controlled' WHERE id=?",Timestamp.from(NOW),id.value());
        assertThatThrownBy(() -> transaction.executeWithoutResult(s -> {
            archival.recordArchived(id,800001); throw new IllegalStateException("controlled rollback");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(jdbc.queryForObject("SELECT state FROM documents WHERE id=?",String.class,id.value())).isEqualTo("SUBMITTED");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM document_passage_index WHERE document_id=?",Long.class,id.value())).isZero();
        archival.recordArchived(id,800001);
        assertThat(jdbc.queryForObject("SELECT state FROM document_passage_index WHERE document_id=?",String.class,id.value())).isEqualTo("PENDING");
    }
    @Test void retryLeaseRecoveryFreshnessReboundAndNoTextDoNotClaimSearchableCoverage() {
        DocumentId id=seed(); var abandoned=claim(id);
        assertThat(index.claim(NOW.plusSeconds(89))).isEmpty();
        var resumed=index.claim(NOW.plusSeconds(91)).orElseThrow();
        assertThat(index.publish(abandoned,revision("Old"),NOW.plusSeconds(92))).isFalse();
        index.fail(resumed,"text.sourceUnavailable",PassageQueries.State.SOURCE_UNAVAILABLE,NOW);
        assertThat(index.status(id,NOW).state()).isEqualTo(PassageQueries.State.SOURCE_UNAVAILABLE);
        assertThat(index.claim(NOW.plusSeconds(60))).isEmpty();
        index.invalidate(id,NOW); assertThat(index.publish(claim(id),revision(""),NOW)).isTrue();
        assertThat(index.status(id,NOW).state()).isEqualTo(PassageQueries.State.NO_TEXT);
        assertThat(index.coverage(NOW).complete()).isFalse();
        index.invalidate(id,NOW); assertThat(index.publish(claim(id),revision("Verified clause"),NOW)).isTrue();
        assertThat(index.status(id,NOW.plusSeconds(86401)).state()).isEqualTo(PassageQueries.State.STALE);
        assertThat(index.search("Verified",null,20,NOW.plusSeconds(86401)).passages()).isEmpty();
        jdbc.update("UPDATE documents SET paperless_document_id=899999,version=version+1 WHERE id=?",id.value());
        assertThat(index.status(id,NOW).state()).isEqualTo(PassageQueries.State.STALE);
        assertThat(index.backfill(NOW,1)).isPositive();
        assertThat(index.status(id,NOW).state()).isEqualTo(PassageQueries.State.PENDING);
        assertThat(index.claim(NOW).orElseThrow().archiveId()).isEqualTo(899999);
    }
    @Test void searchReadCommandsAndWorkerAuthenticateEveryEntryPoint() {
        DocumentId id=seed(); var rev=revision("Read provenance"); assertThat(index.publish(claim(id),rev,NOW)).isTrue();
        Actor outsider=new Actor(MemberId.random()); Actor process=Actor.householdProcess("unrelated-process");
        for (Actor unauthorized:List.of(outsider,process)) {
            assertThatThrownBy(() -> queries.search(unauthorized,"Read",null,10)).isInstanceOf(NotAuthorizedException.class);
            assertThatThrownBy(() -> queries.read(unauthorized,id,rev.revision(),0)).isInstanceOf(NotAuthorizedException.class);
            assertThatThrownBy(() -> queries.coverage(unauthorized)).isInstanceOf(NotAuthorizedException.class);
            assertThatThrownBy(() -> queries.status(unauthorized,id)).isInstanceOf(NotAuthorizedException.class);
            assertThatThrownBy(() -> commands.retry(unauthorized,id)).isInstanceOf(NotAuthorizedException.class);
            assertThatThrownBy(() -> commands.rebuild(unauthorized)).isInstanceOf(NotAuthorizedException.class);
            assertThatThrownBy(() -> worker.sweep(unauthorized)).isInstanceOf(NotAuthorizedException.class);
        }
        ApiClient api=new ApiClient(port);
        assertThat(api.get("/api/documents/passages/coverage").status()).isEqualTo(401);
        assertThat(api.login(ANNA,PASSWORD)).isTrue();
        assertThat(api.get("/api/documents/passages/coverage").status()).isEqualTo(200);
        assertThat(api.get("/api/documents/passages?query=Read&limit=51").status()).isEqualTo(400);
    }
    @Test void boundedBackfillRebuildAndContinuationSurviveRestartsAndConcurrentClaims() throws Exception {
        DocumentId a=seed(), b=seed();
        try (var threads=Executors.newFixedThreadPool(2)) {
            var first=threads.submit(() -> index.claim(NOW)); var second=threads.submit(() -> index.claim(NOW));
            var claims=List.of(first.get().orElseThrow(),second.get().orElseThrow());
            assertThat(claims.stream().map(PassageIndex.Claim::id)).containsExactlyInAnyOrder(a,b);
            for (var claim:claims) { assertThat(index.publish(claim,revision("Shared search text"),NOW)).isTrue(); }
        }
        var page=index.search("Shared",null,1,NOW); assertThat(page.nextCursor()).isNotNull();
        var next=index.search("Shared",page.nextCursor(),1,NOW);
        assertThat(next.passages().getFirst().documentId()).isNotEqualTo(page.passages().getFirst().documentId());
        assertThat(next.nextCursor()).isNull();
        index.rebuild(NOW);
        assertThat(index.read(a,revision("Shared search text").revision(),0,NOW)).isEmpty();
        assertThatThrownBy(() -> index.search("Shared",page.nextCursor(),1,NOW)).isInstanceOf(InvalidInputException.class);
        assertThat(index.backfill(NOW,1)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM document_passage_index i,document_passage_control c WHERE i.document_id IN (?,?) AND i.build_epoch=c.build_epoch",Long.class,a.value(),b.value())).isEqualTo(1);
        assertThat(index.backfill(NOW,1)).isEqualTo(1);
        assertThat(index.backfill(NOW,1)).isZero();
    }
    @Test void controlledWorkerReprocessesOcrWithUnchangedOriginalAndReportsOutage() throws Exception {
        DocumentId id=seed();
        var file=DATA.resolve("passage-"+id+".jpg");
        java.nio.file.Files.write(file,("synthetic image "+id).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        archive.ocrText(java.nio.file.Files.readAllBytes(file),"Original cancellation clause");
        var accepted=(dev.aegis.documents.application.ports.PaperlessArchive.Accepted)archive.submit(file,"Scan","scan.jpg","image/jpeg");
        archive.checkTask(accepted.taskId());
        int external=((dev.aegis.documents.application.ports.PaperlessArchive.Completed)archive.checkTask(accepted.taskId())).paperlessDocumentId();
        try {
            jdbc.update("UPDATE documents SET paperless_document_id=? WHERE id=?",external,id.value());
            index.request(id,NOW);
            assertThat(worker.sweep(Actor.householdProcess(PassageIndexWorker.PROCESS)).published()).isEqualTo(1);
            var before=queries.status(actor(),id);
            archive.reprocess(external,"Renewed termination clause R-123/456",null);
            commands.retry(actor(),id);
            assertThat(worker.sweep(Actor.householdProcess(PassageIndexWorker.PROCESS)).published()).isEqualTo(1);
            assertThat(queries.status(actor(),id).revision()).isNotEqualTo(before.revision());
            assertThat(queries.search(actor(),"R123456",null,20).passages()).hasSize(1);
            archive.available=false; commands.retry(actor(),id);
            assertThat(worker.sweep(Actor.householdProcess(PassageIndexWorker.PROCESS)).failed()).isEqualTo(1);
            assertThat(queries.status(actor(),id).state()).isEqualTo(PassageQueries.State.SOURCE_UNAVAILABLE);
            assertThat(queries.search(actor(),"R123456",null,20).coverage().complete()).isFalse();
        } finally { archive.available=true; archive.purge(external); java.nio.file.Files.deleteIfExists(file); }
    }
    @Test void memberTrashRestoreInvalidatesRunningWorkerAndTerminalFailureRequiresRetry() {
        DocumentId id=seed(); var beforeTrash=claim(id);
        documentCommands.moveToTrash(actor(),id,0);
        assertThat(index.publish(beforeTrash,revision("Must not return"),NOW)).isFalse();
        documentCommands.restoreFromTrash(actor(),id,1);
        assertThat(index.publish(beforeTrash,revision("Must not return"),NOW)).isFalse();
        index.invalidate(id,NOW);
        Instant at=NOW;
        for (int attempt=1;attempt<=5;attempt++) {
            var claim=index.claim(at).orElseThrow();
            assertThat(claim.attempts()).isEqualTo(attempt);
            index.fail(claim,"text.controlledFailure",PassageQueries.State.FAILED,at);
            at=at.plusSeconds(3601);
        }
        assertThat(index.claim(at.plusSeconds(86400))).isEmpty();
        index.invalidate(id,NOW);
        assertThat(claim(id).attempts()).isEqualTo(1);
    }
    @Test void expiredLeaseAndOldChunkVersionsCannotBePublishedOrRead() {
        DocumentId id=seed(); var expired=claim(id);
        assertThat(index.publish(expired,revision("Late text"),NOW.plusSeconds(91))).isFalse();
        var renewed=index.claim(NOW.plusSeconds(91)).orElseThrow();
        assertThat(index.publish(renewed,revision("Current text"),NOW.plusSeconds(92))).isTrue();
        jdbc.update("UPDATE document_text_revisions SET chunk_version='obsolete' WHERE document_id=?",id.value());
        assertThat(index.status(id,NOW.plusSeconds(93)).state()).isEqualTo(PassageQueries.State.STALE);
        assertThat(index.search("Current",null,20,NOW.plusSeconds(93)).passages()).isEmpty();
        assertThat(index.backfill(NOW,1)).isEqualTo(1);
        assertThat(index.status(id,NOW).state()).isEqualTo(PassageQueries.State.PENDING);
    }
    @Test void syntheticThousandDocumentsFindMislabeledGermanEnglishAndEndOfLongDocument() {
        long start=System.nanoTime();
        for (int count=0;count<1000;count++) {
            DocumentId id=seed();
            String text=count==999?"Routine paragraph.\n\n".repeat(2500)+"Müller K-2026/004 Kündigungsfristen: drei Monate. Cancellation requires written notice.":"Routine unrelated household text "+count;
            assertThat(index.publish(claim(id),revision(text),NOW)).isTrue();
            if (count==499 || count==999) {
                assertThat(index.search("unrelated",null,50,NOW).passages()).hasSize(50);
            }
        }
        for (String query:List.of("Kündigungsfrist","cancellations","Muller","K2026004")) {
            assertThat(index.search(query,null,20,NOW).passages()).anySatisfy(p -> {
                assertThat(p.documentId()).isEqualTo(ids.getLast());
                assertThat(p.original()).contains("written notice"); assertThat(p.start()).isGreaterThan(40000);
                assertThat(p.startPage()).isNull();
            });
        }
        long searchStart=System.nanoTime();
        for (int i=0;i<100;i++) { index.search("K2026004",null,20,NOW); }
        System.out.printf("Synthetic passage corpus: documents=1000 index_ms=%d mean_search_ms=%.2f%n",
                (searchStart-start)/1_000_000,(System.nanoTime()-searchStart)/100_000_000.0);
        jdbc.execute("SET enable_seqscan=off");
        try {
            String plan=String.join("\n",jdbc.queryForList("EXPLAIN SELECT * FROM document_passages WHERE german @@ plainto_tsquery('german','Kündigung')",String.class));
            assertThat(plan).contains("document_passages_german");
        } finally { jdbc.execute("RESET enable_seqscan"); }
    }
}
