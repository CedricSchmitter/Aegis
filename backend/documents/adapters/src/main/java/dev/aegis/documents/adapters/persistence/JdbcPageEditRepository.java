package dev.aegis.documents.adapters.persistence;

import dev.aegis.documents.domain.Checksum;
import dev.aegis.documents.domain.Subject;
import dev.aegis.documents.domain.SubjectKind;
import dev.aegis.documents.domain.pages.PageEdit;
import dev.aegis.documents.domain.pages.PageEditOutput;
import dev.aegis.documents.domain.pages.PageEditPlan;
import dev.aegis.documents.domain.pages.PageEditRepository;
import dev.aegis.documents.domain.pages.PageRef;
import dev.aegis.shared.DocumentId;
import dev.aegis.shared.MemberId;
import dev.aegis.shared.StaleVersionException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * Page editor operations in PostgreSQL. The plan is stored as versioned JSON naming sources by their stable ids; the
 * results as JSON as well, since they are written once when the operation completes.
 */
@Component
public class JdbcPageEditRepository implements PageEditRepository {
    static final int PLAN_SCHEMA = 1;
    private static final String SELECT = """
            SELECT id, requested_by, requested_at, plan::text AS plan_json, scan_path, scan_checksum, scan_pages,
                   state, attempts, failure_code, finished_at, results::text AS results_json, version
              FROM document_page_edits""";
    private static final TypeReference<List<ResultJson>> RESULTS = new TypeReference<>() {
    };

    record PlanJson(int schema, List<OutputJson> outputs) {
    }

    record OutputJson(String title, boolean titleChosen, UUID categoryId, List<SubjectJson> subjects,
            List<PageJson> pages) {
    }

    record SubjectJson(String kind, UUID member) {
    }

    /** {@code document} is null for a scanned page. */
    record PageJson(UUID document, int index, int rotation) {
    }

    record ResultJson(int output, UUID document, boolean existing) {
    }

    private final JdbcClient jdbc;
    private final ObjectMapper json;

    JdbcPageEditRepository(JdbcClient jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    @Override
    public Optional<PageEdit> findById(UUID id) {
        return jdbc.sql(SELECT + " WHERE id = ?").param(id).query(this::edit).optional();
    }

    @Override
    public boolean insert(PageEdit edit) {
        int inserted = jdbc.sql("""
                INSERT INTO document_page_edits (id, requested_by, requested_at, plan, scan_path, scan_checksum,
                       scan_pages, state, attempts, failure_code, finished_at, results, version)
                VALUES (:id, :requestedBy, :requestedAt, CAST(:plan AS jsonb), :scanPath, :scanChecksum, :scanPages,
                       :state, :attempts, :failureCode, :finishedAt, CAST(:results AS jsonb), 1)
                ON CONFLICT (id) DO NOTHING""").params(values(edit)).update();
        return inserted == 1;
    }

    @Override
    public void save(PageEdit edit) {
        int updated = jdbc.sql("""
                UPDATE document_page_edits SET state = :state, attempts = :attempts, failure_code = :failureCode,
                       finished_at = :finishedAt, results = CAST(:results AS jsonb), version = version + 1
                 WHERE id = :id AND version = :version""").params(values(edit)).update();
        if (updated == 0) {
            long stored = jdbc.sql("SELECT version FROM document_page_edits WHERE id = ?").param(edit.id())
                    .query(Long.class).optional().orElse(-1L);
            throw new StaleVersionException("pageEdit", edit.id().toString(), edit.version(), stored);
        }
    }

    private Map<String, Object> values(PageEdit edit) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("id", edit.id());
        values.put("requestedBy", edit.requestedBy().value());
        values.put("requestedAt", Timestamp.from(edit.requestedAt()));
        values.put("plan", json.writeValueAsString(planJson(edit.plan())));
        values.put("scanPath", edit.scan().map(PageEdit.Scan::path).orElse(null));
        values.put("scanChecksum", edit.scan().map(scan -> scan.checksum().sha256Hex()).orElse(null));
        values.put("scanPages", edit.scan().map(PageEdit.Scan::pageCount).orElse(null));
        values.put("state", edit.state().name());
        values.put("attempts", edit.attempts());
        values.put("failureCode", edit.failureCode().orElse(null));
        values.put("finishedAt", edit.finishedAt().map(Timestamp::from).orElse(null));
        values.put("results", json.writeValueAsString(edit.results().stream()
                .map(result -> new ResultJson(result.output(), result.document().value(), result.existing()))
                .toList()));
        values.put("version", edit.version());
        return values;
    }

    private PageEdit edit(ResultSet rs, int rowNumber) throws SQLException {
        PageEdit.Scan scan = rs.getString("scan_path") == null ? null : new PageEdit.Scan(rs.getString("scan_path"),
                Checksum.of(rs.getString("scan_checksum")), rs.getInt("scan_pages"));
        Timestamp finished = rs.getTimestamp("finished_at");
        List<PageEdit.Result> results = json.readValue(rs.getString("results_json"), RESULTS).stream()
                .map(result -> new PageEdit.Result(result.output(), new DocumentId(result.document()),
                        result.existing()))
                .toList();
        return PageEdit.rehydrate(rs.getObject("id", UUID.class), new MemberId(rs.getObject("requested_by",
                UUID.class)), rs.getTimestamp("requested_at").toInstant(),
                plan(json.readValue(rs.getString("plan_json"), PlanJson.class)), scan,
                PageEdit.State.valueOf(rs.getString("state")), rs.getInt("attempts"), rs.getString("failure_code"),
                finished == null ? null : finished.toInstant(), results, rs.getLong("version"));
    }

    static PlanJson planJson(PageEditPlan plan) {
        return new PlanJson(PLAN_SCHEMA, plan.outputs().stream().map(output -> new OutputJson(output.title(),
                output.titleChosen(), output.categoryId(), output.subjects().stream()
                        .map(subject -> new SubjectJson(subject.kind().name(),
                                subject.memberId().map(MemberId::value).orElse(null)))
                        .sorted((a, b) -> (a.kind() + a.member()).compareTo(b.kind() + b.member())).toList(),
                output.pages().stream().map(page -> new PageJson(page.source().map(DocumentId::value).orElse(null),
                        page.pageIndex(), page.rotation())).toList())).toList());
    }

    static PageEditPlan plan(PlanJson stored) {
        if (stored.schema() != PLAN_SCHEMA) {
            throw new IllegalStateException("unknown page edit plan schema " + stored.schema());
        }
        return new PageEditPlan(stored.outputs().stream().map(output -> {
            LinkedHashSet<Subject> subjects = new LinkedHashSet<>();
            for (SubjectJson subject : output.subjects()) {
                subjects.add(new Subject(SubjectKind.valueOf(subject.kind()),
                        subject.member() == null ? null : new MemberId(subject.member())));
            }
            return new PageEditOutput(output.title(), output.titleChosen(), output.categoryId(), subjects,
                    output.pages().stream().map(page -> page.document() == null
                            ? PageRef.ofScan(page.index(), page.rotation())
                            : PageRef.ofDocument(new DocumentId(page.document()), page.index(), page.rotation()))
                            .toList());
        }).toList());
    }
}
