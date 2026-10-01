package dev.aegis.documents.adapters.persistence;

import dev.aegis.documents.domain.bulk.BulkAnalysis;
import dev.aegis.documents.domain.bulk.BulkAnalysis.Item;
import dev.aegis.documents.domain.bulk.BulkAnalysis.ItemState;
import dev.aegis.documents.domain.bulk.BulkAnalysis.Outcome;
import dev.aegis.documents.domain.bulk.BulkAnalysis.State;
import dev.aegis.documents.domain.bulk.BulkAnalysisRepository;
import dev.aegis.documents.domain.bulk.BulkScope;
import dev.aegis.shared.DocumentId;
import dev.aegis.shared.InvalidInputException;
import dev.aegis.shared.MemberId;
import dev.aegis.shared.StaleVersionException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Bulk analyses in PostgreSQL with explicit SQL. The header carries the version; a save writes only the items that
 * changed, so following a run of a few hundred documents rewrites a handful of rows per step.
 */
@Component
public class JdbcBulkAnalysisRepository implements BulkAnalysisRepository {
    private static final String SELECT = """
            SELECT id, requested_by, requested_at, scope_kind, scope_folder_id, include_subfolders, batch_size, state,
                   stop_code, cancelled_by, cancelled_at, finished_at, version
              FROM document_bulk_analyses""";

    private final JdbcClient jdbc;

    JdbcBulkAnalysisRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    private record Header(UUID id, MemberId requestedBy, Instant requestedAt, BulkScope scope, int batchSize,
            State state, String stopCode, MemberId cancelledBy, Instant cancelledAt, Instant finishedAt,
            long version) {
    }

    @Override
    public Optional<BulkAnalysis> findById(UUID id) {
        return jdbc.sql(SELECT + " WHERE id = ?").param(id).query(this::header).optional().map(this::withItems);
    }

    @Override
    public Optional<BulkAnalysis> findByIdForUpdate(UUID id) {
        return jdbc.sql(SELECT + " WHERE id = ? FOR UPDATE").param(id).query(this::header).optional()
                .map(this::withItems);
    }

    @Override
    public Optional<BulkAnalysis> findRunning() {
        return jdbc.sql(SELECT + " WHERE state = 'RUNNING'").query(this::header).optional().map(this::withItems);
    }

    @Override
    public List<BulkAnalysis> findRecent(int limit) {
        return jdbc.sql(SELECT + " ORDER BY requested_at DESC, id LIMIT ?").param(limit).query(this::header).list()
                .stream().map(this::withItems).toList();
    }

    @Override
    public void save(BulkAnalysis bulk) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("id", bulk.id());
        values.put("requestedBy", bulk.requestedBy().value());
        values.put("requestedAt", Timestamp.from(bulk.requestedAt()));
        values.put("scopeKind", bulk.scope().kind().name());
        values.put("scopeFolderId", bulk.scope().folderId());
        values.put("includeSubfolders", bulk.scope().includeSubfolders());
        values.put("batchSize", bulk.batchSize());
        values.put("state", bulk.state().name());
        values.put("stopCode", bulk.stopCode().orElse(null));
        values.put("cancelledBy", bulk.cancelledBy().map(MemberId::value).orElse(null));
        values.put("cancelledAt", bulk.cancelledAt().map(Timestamp::from).orElse(null));
        values.put("finishedAt", bulk.finishedAt().map(Timestamp::from).orElse(null));
        values.put("version", bulk.version());
        if (bulk.isNew()) {
            try {
                jdbc.sql("""
                        INSERT INTO document_bulk_analyses (id, requested_by, requested_at, scope_kind,
                               scope_folder_id, include_subfolders, batch_size, state, stop_code, cancelled_by,
                               cancelled_at, finished_at, version)
                        VALUES (:id, :requestedBy, :requestedAt, :scopeKind, :scopeFolderId, :includeSubfolders,
                               :batchSize, :state, :stopCode, :cancelledBy, :cancelledAt, :finishedAt, 1)""")
                        .params(values).update();
            } catch (DuplicateKeyException running) {
                throw new InvalidInputException("analysis.bulk.active", "Another bulk analysis is running");
            }
        } else {
            int updated = jdbc.sql("""
                    UPDATE document_bulk_analyses SET state = :state, stop_code = :stopCode,
                           cancelled_by = :cancelledBy, cancelled_at = :cancelledAt, finished_at = :finishedAt,
                           version = version + 1
                     WHERE id = :id AND version = :version""").params(values).update();
            if (updated == 0) {
                long stored = jdbc.sql("SELECT version FROM document_bulk_analyses WHERE id = ?").param(bulk.id())
                        .query(Long.class).optional().orElse(-1L);
                throw new StaleVersionException("bulkAnalysis", bulk.id().toString(), bulk.version(), stored);
            }
        }
        for (Item item : bulk.changedItems()) {
            jdbc.sql("""
                    INSERT INTO document_bulk_analysis_items (bulk_id, document_id, position, state, generation,
                           outcome, code, updated_at)
                    VALUES (:bulkId, :documentId, :position, :state, :generation, :outcome, :code, :updatedAt)
                    ON CONFLICT (bulk_id, document_id) DO UPDATE SET state = EXCLUDED.state,
                           generation = EXCLUDED.generation, outcome = EXCLUDED.outcome, code = EXCLUDED.code,
                           updated_at = EXCLUDED.updated_at""")
                    .param("bulkId", bulk.id())
                    .param("documentId", item.documentId().value())
                    .param("position", item.position())
                    .param("state", item.state().name())
                    .param("generation", item.generation())
                    .param("outcome", item.outcome() == null ? null : item.outcome().name())
                    .param("code", item.code())
                    .param("updatedAt", Timestamp.from(item.updatedAt()))
                    .update();
        }
    }

    private Header header(ResultSet rs, int row) throws SQLException {
        UUID cancelledBy = rs.getObject("cancelled_by", UUID.class);
        return new Header(rs.getObject("id", UUID.class), new MemberId(rs.getObject("requested_by", UUID.class)),
                rs.getTimestamp("requested_at").toInstant(),
                new BulkScope(BulkScope.Kind.valueOf(rs.getString("scope_kind")),
                        rs.getObject("scope_folder_id", UUID.class), rs.getBoolean("include_subfolders")),
                rs.getInt("batch_size"), State.valueOf(rs.getString("state")), rs.getString("stop_code"),
                cancelledBy == null ? null : new MemberId(cancelledBy), instant(rs.getTimestamp("cancelled_at")),
                instant(rs.getTimestamp("finished_at")), rs.getLong("version"));
    }

    private BulkAnalysis withItems(Header h) {
        List<Item> items = new ArrayList<>(jdbc.sql("""
                SELECT document_id, position, state, generation, outcome, code, updated_at
                  FROM document_bulk_analysis_items WHERE bulk_id = ? ORDER BY position""").param(h.id())
                .query((rs, row) -> {
                    String outcome = rs.getString("outcome");
                    return new Item(new DocumentId(rs.getObject("document_id", UUID.class)), rs.getInt("position"),
                            ItemState.valueOf(rs.getString("state")), rs.getInt("generation"),
                            outcome == null ? null : Outcome.valueOf(outcome), rs.getString("code"),
                            rs.getTimestamp("updated_at").toInstant());
                }).list());
        return BulkAnalysis.rehydrate(h.id(), h.requestedBy(), h.requestedAt(), h.scope(), h.batchSize(), h.state(),
                h.stopCode(), h.cancelledBy(), h.cancelledAt(), h.finishedAt(), items, h.version());
    }

    private static Instant instant(Timestamp value) {
        return value == null ? null : value.toInstant();
    }
}
