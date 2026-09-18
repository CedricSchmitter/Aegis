package dev.aegis.documents.adapters.persistence;

import dev.aegis.documents.domain.Subject;
import dev.aegis.documents.domain.SubjectKind;
import dev.aegis.documents.domain.analysis.AnalysisTypes.DateStatus;
import dev.aegis.documents.domain.analysis.AnalysisTypes.Decision;
import dev.aegis.documents.domain.analysis.AnalysisTypes.Flag;
import dev.aegis.documents.domain.analysis.AnalysisTypes.Status;
import dev.aegis.documents.domain.analysis.AnalysisTypes.Trigger;
import dev.aegis.documents.domain.analysis.AnalysisValues.DocumentDate;
import dev.aegis.documents.domain.analysis.AnalysisValues.GeneratedValues;
import dev.aegis.documents.domain.analysis.AnalysisValues.LabelCandidate;
import dev.aegis.documents.domain.analysis.AnalysisValues.SubjectSuggestion;
import dev.aegis.documents.domain.analysis.DocumentAnalysis;
import dev.aegis.documents.domain.analysis.DocumentAnalysisRepository;
import dev.aegis.documents.domain.catalog.Facet;
import dev.aegis.documents.domain.catalog.LabelId;
import dev.aegis.shared.DocumentId;
import dev.aegis.shared.MemberId;
import dev.aegis.shared.StaleVersionException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * Document analyses in PostgreSQL with explicit SQL. Suggestion lists and history are small JSON documents with
 * stable English field names; their shapes are private to this adapter and mapped explicitly.
 */
@Component
public class JdbcDocumentAnalysisRepository implements DocumentAnalysisRepository {
    private static final TypeReference<List<Map<String, Object>>> ROWS = new TypeReference<>() {
    };
    private static final TypeReference<List<String>> STRINGS = new TypeReference<>() {
    };
    private static final String SELECT = """
            SELECT document_id, status, generation, trigger, requested_by, requested_at, unavailable_code,
                   completed_at, completed_run_ref, model, applied_labels, suggested_labels, title, title_decision,
                   title_before, document_date, document_date_evidence, document_date_status, short_topic,
                   correspondent_label_id, flags::text AS flags_json, candidates::text AS candidates_json,
                   subjects::text AS subjects_json, history::text AS history_json, filename_override, filename_by,
                   filename_at, version
              FROM document_analyses""";

    private final JdbcClient jdbc;
    private final ObjectMapper json;

    JdbcDocumentAnalysisRepository(JdbcClient jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    @Override
    public Optional<DocumentAnalysis> findById(DocumentId id) {
        return Optional.ofNullable(findAll(List.of(id)).get(id));
    }

    @Override
    public Map<DocumentId, DocumentAnalysis> findAll(Collection<DocumentId> ids) {
        Map<DocumentId, DocumentAnalysis> result = new LinkedHashMap<>();
        if (ids.isEmpty()) {
            return result;
        }
        jdbc.sql(SELECT + " WHERE document_id IN (:ids)").param("ids", ids.stream().map(DocumentId::value).toList())
                .query((rs, row) -> map(rs)).list().forEach(analysis -> result.put(analysis.documentId(), analysis));
        return result;
    }

    @Override
    public void save(DocumentAnalysis analysis) {
        DocumentAnalysis.Snapshot s = analysis.snapshot();
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("documentId", analysis.documentId().value());
        values.put("status", s.status().name());
        values.put("generation", s.generation());
        values.put("trigger", s.trigger() == null ? null : s.trigger().name());
        values.put("requestedBy", member(s.requestedBy()));
        values.put("requestedAt", timestamp(s.requestedAt()));
        values.put("unavailableCode", s.unavailableCode());
        values.put("completedAt", timestamp(s.completedAt()));
        values.put("completedRunRef", s.completedRunRef());
        values.put("model", s.model());
        values.put("appliedLabels", s.appliedLabels());
        values.put("suggestedLabels", s.suggestedLabels());
        values.put("title", s.title());
        values.put("titleDecision", s.titleDecision() == null ? null : s.titleDecision().name());
        values.put("titleBefore", s.titleBefore());
        values.put("documentDate", s.documentDate().value() == null ? null
                : java.sql.Date.valueOf(s.documentDate().value()));
        values.put("documentDateEvidence", s.documentDate().evidence());
        values.put("documentDateStatus", s.documentDate().status().name());
        values.put("shortTopic", s.shortTopic());
        values.put("correspondent", s.correspondent() == null ? null : s.correspondent().value());
        values.put("flags", json.writeValueAsString(s.flags().stream().map(Enum::name).sorted().toList()));
        values.put("candidates", json.writeValueAsString(s.candidates().stream().map(this::candidate).toList()));
        values.put("subjects", json.writeValueAsString(s.subjects().stream().map(this::subject).toList()));
        values.put("history", json.writeValueAsString(s.history().stream().map(this::generated).toList()));
        values.put("filenameOverride", s.filenameOverride());
        values.put("filenameBy", member(s.filenameBy()));
        values.put("filenameAt", timestamp(s.filenameAt()));
        values.put("version", analysis.version());
        int updated = jdbc.sql("""
                UPDATE document_analyses SET status = :status, generation = :generation, trigger = :trigger,
                       requested_by = :requestedBy, requested_at = :requestedAt, unavailable_code = :unavailableCode,
                       completed_at = :completedAt, completed_run_ref = :completedRunRef, model = :model,
                       applied_labels = :appliedLabels, suggested_labels = :suggestedLabels, title = :title,
                       title_decision = :titleDecision, title_before = :titleBefore, document_date = :documentDate,
                       document_date_evidence = :documentDateEvidence,
                       document_date_status = :documentDateStatus, short_topic = :shortTopic,
                       correspondent_label_id = :correspondent, flags = CAST(:flags AS jsonb),
                       candidates = CAST(:candidates AS jsonb), subjects = CAST(:subjects AS jsonb),
                       history = CAST(:history AS jsonb), filename_override = :filenameOverride,
                       filename_by = :filenameBy, filename_at = :filenameAt, version = version + 1
                 WHERE document_id = :documentId AND version = :version""").params(values).update();
        if (updated > 0) {
            return;
        }
        Optional<Long> stored = jdbc.sql("SELECT version FROM document_analyses WHERE document_id = ?")
                .param(analysis.documentId().value()).query(Long.class).optional();
        if (stored.isPresent() || analysis.version() != 0) {
            throw new StaleVersionException("documentAnalysis", analysis.documentId().toString(), analysis.version(),
                    stored.orElse(-1L));
        }
        try {
            insert(values);
        } catch (DuplicateKeyException concurrent) {
            throw new StaleVersionException("documentAnalysis", analysis.documentId().toString(), 0, -1);
        }
    }

    private void insert(Map<String, Object> values) {
        jdbc.sql("""
                INSERT INTO document_analyses (document_id, status, generation, trigger, requested_by, requested_at,
                       unavailable_code, completed_at, completed_run_ref, model, applied_labels, suggested_labels,
                       title, title_decision, title_before, document_date, document_date_evidence,
                       document_date_status, short_topic, correspondent_label_id, flags, candidates, subjects,
                       history, filename_override, filename_by, filename_at, version)
                VALUES (:documentId, :status, :generation, :trigger, :requestedBy, :requestedAt, :unavailableCode,
                       :completedAt, :completedRunRef, :model, :appliedLabels, :suggestedLabels, :title,
                       :titleDecision, :titleBefore, :documentDate, :documentDateEvidence, :documentDateStatus,
                       :shortTopic, :correspondent, CAST(:flags AS jsonb), CAST(:candidates AS jsonb),
                       CAST(:subjects AS jsonb), CAST(:history AS jsonb), :filenameOverride, :filenameBy,
                       :filenameAt, 1)""").params(values).update();
    }

    private DocumentAnalysis map(ResultSet rs) throws SQLException {
        Set<Flag> flags = EnumSet.noneOf(Flag.class);
        json.readValue(rs.getString("flags_json"), STRINGS).forEach(flag -> flags.add(Flag.valueOf(flag)));
        List<LabelCandidate> candidates = new ArrayList<>();
        for (Map<String, Object> c : json.readValue(rs.getString("candidates_json"), ROWS)) {
            candidates.add(new LabelCandidate(Facet.valueOf(text(c, "facet")), text(c, "name"), text(c, "evidence"),
                    number(c, "confidence"), Decision.valueOf(text(c, "decision")),
                    Optional.ofNullable(text(c, "labelId")).map(v -> new LabelId(UUID.fromString(v))).orElse(null),
                    memberOf(text(c, "decidedBy")), instant(text(c, "decidedAt"))));
        }
        List<SubjectSuggestion> subjects = new ArrayList<>();
        for (Map<String, Object> x : json.readValue(rs.getString("subjects_json"), ROWS)) {
            subjects.add(new SubjectSuggestion(new Subject(SubjectKind.valueOf(text(x, "kind")),
                    memberOf(text(x, "memberId"))), number(x, "confidence"), text(x, "evidence"),
                    Decision.valueOf(text(x, "decision")), memberOf(text(x, "decidedBy")),
                    instant(text(x, "decidedAt"))));
        }
        List<GeneratedValues> history = new ArrayList<>();
        for (Map<String, Object> h : json.readValue(rs.getString("history_json"), ROWS)) {
            history.add(new GeneratedValues(((Number) h.get("generation")).intValue(), text(h, "runRef"),
                    text(h, "model"), instant(text(h, "completedAt")), text(h, "title"),
                    Optional.ofNullable(text(h, "documentDate")).map(LocalDate::parse).orElse(null),
                    text(h, "shortTopic")));
        }
        java.sql.Date date = rs.getDate("document_date");
        String trigger = rs.getString("trigger");
        String titleDecision = rs.getString("title_decision");
        UUID correspondent = rs.getObject("correspondent_label_id", UUID.class);
        DocumentAnalysis.Snapshot snapshot = new DocumentAnalysis.Snapshot(Status.valueOf(rs.getString("status")),
                rs.getInt("generation"), trigger == null ? null : Trigger.valueOf(trigger),
                memberOf(rs.getObject("requested_by", UUID.class)), instant(rs.getTimestamp("requested_at")),
                rs.getString("unavailable_code"), instant(rs.getTimestamp("completed_at")),
                rs.getString("completed_run_ref"), rs.getString("model"), rs.getInt("applied_labels"),
                rs.getInt("suggested_labels"), rs.getString("title"),
                titleDecision == null ? null : Decision.valueOf(titleDecision), rs.getString("title_before"),
                new DocumentDate(date == null ? null : date.toLocalDate(), rs.getString("document_date_evidence"),
                        DateStatus.valueOf(rs.getString("document_date_status"))),
                rs.getString("short_topic"), correspondent == null ? null : new LabelId(correspondent), flags,
                candidates, subjects, rs.getString("filename_override"),
                memberOf(rs.getObject("filename_by", UUID.class)), instant(rs.getTimestamp("filename_at")),
                history);
        return DocumentAnalysis.rehydrate(new DocumentId(rs.getObject("document_id", UUID.class)), snapshot,
                rs.getLong("version"));
    }

    private Map<String, Object> candidate(LabelCandidate c) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("facet", c.facet().name());
        row.put("name", c.name());
        row.put("evidence", c.evidence());
        row.put("confidence", c.confidence());
        row.put("decision", c.decision().name());
        row.put("labelId", c.labelId() == null ? null : c.labelId().toString());
        row.put("decidedBy", c.decidedBy() == null ? null : c.decidedBy().toString());
        row.put("decidedAt", c.decidedAt() == null ? null : c.decidedAt().toString());
        return row;
    }

    private Map<String, Object> subject(SubjectSuggestion x) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("kind", x.subject().kind().name());
        row.put("memberId", x.subject().memberId().map(MemberId::toString).orElse(null));
        row.put("confidence", x.confidence());
        row.put("evidence", x.evidence());
        row.put("decision", x.decision().name());
        row.put("decidedBy", x.decidedBy() == null ? null : x.decidedBy().toString());
        row.put("decidedAt", x.decidedAt() == null ? null : x.decidedAt().toString());
        return row;
    }

    private Map<String, Object> generated(GeneratedValues h) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("generation", h.generation());
        row.put("runRef", h.runRef());
        row.put("model", h.model());
        row.put("completedAt", h.completedAt() == null ? null : h.completedAt().toString());
        row.put("title", h.title());
        row.put("documentDate", h.documentDate() == null ? null : h.documentDate().toString());
        row.put("shortTopic", h.shortTopic());
        return row;
    }

    private static String text(Map<String, Object> row, String key) {
        Object value = row.get(key);
        return value == null ? null : value.toString();
    }

    private static Double number(Map<String, Object> row, String key) {
        return row.get(key) instanceof Number n ? n.doubleValue() : null;
    }

    private static MemberId memberOf(String value) {
        return value == null ? null : MemberId.of(value);
    }

    private static MemberId memberOf(UUID value) {
        return value == null ? null : new MemberId(value);
    }

    private static UUID member(MemberId member) {
        return member == null ? null : member.value();
    }

    private static Instant instant(String value) {
        return value == null ? null : Instant.parse(value);
    }

    private static Instant instant(Timestamp value) {
        return value == null ? null : value.toInstant();
    }

    private static Timestamp timestamp(Instant value) {
        return value == null ? null : Timestamp.from(value);
    }
}
