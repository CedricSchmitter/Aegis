package dev.aegis.assistant.adapters.persistence;

import dev.aegis.assistant.domain.RunId;
import dev.aegis.assistant.domain.chat.ChatAnswer;
import dev.aegis.assistant.domain.chat.ChatAnswer.Check;
import dev.aegis.assistant.domain.chat.ChatAnswer.Citation;
import dev.aegis.assistant.domain.chat.ChatAnswer.Evidence;
import dev.aegis.assistant.domain.chat.ChatAnswer.SourceKind;
import dev.aegis.assistant.domain.chat.ChatTurn;
import dev.aegis.assistant.domain.chat.Conversation;
import dev.aegis.assistant.domain.chat.ConversationRepository;
import dev.aegis.shared.MemberId;
import dev.aegis.shared.ProductLocale;
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
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * Conversations with their turns. The conversation row carries the version; turns are upserted with it, so a
 * conversation and its questions always change together. The answer is stored as JSON in an explicit shape.
 */
@Component
public class JdbcConversationRepository implements ConversationRepository {
    private static final TypeReference<Map<String, Object>> OBJECT = new TypeReference<>() {
    };

    private final JdbcClient jdbc;
    private final ObjectMapper json;

    JdbcConversationRepository(JdbcClient jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    @Override
    public Optional<Conversation> findById(UUID id) {
        Optional<ConversationRow> row = jdbc.sql("""
                SELECT id, owner, title, created_at, updated_at, version FROM chat_conversations WHERE id = ?""")
                .param(id).query(JdbcConversationRepository::row).optional();
        if (row.isEmpty()) {
            return Optional.empty();
        }
        List<ChatTurn> turns = jdbc.sql("""
                SELECT id, seq, question, locale, asked_at, run_id, answer::text AS answer_json, answered_at
                  FROM chat_turns WHERE conversation_id = ? ORDER BY seq""")
                .param(id).query(this::turn).list();
        ConversationRow found = row.get();
        return Optional.of(Conversation.restore(found.id, new MemberId(found.owner), found.createdAt, found.title,
                turns, found.updatedAt, found.version));
    }

    @Override
    public List<ConversationHeader> findByOwner(MemberId owner, int limit) {
        return jdbc.sql("""
                SELECT c.id, c.title, c.created_at, c.updated_at,
                       (SELECT count(*) FROM chat_turns t WHERE t.conversation_id = c.id) AS turns
                  FROM chat_conversations c
                 WHERE c.owner = ?
                 ORDER BY c.updated_at DESC, c.id
                 LIMIT ?""")
                .params(owner.value(), limit)
                .query((rs, index) -> new ConversationHeader(rs.getObject("id", UUID.class), rs.getString("title"),
                        instant(rs, "created_at"), instant(rs, "updated_at"), rs.getInt("turns")))
                .list();
    }

    @Override
    public void save(Conversation conversation) {
        long expected = conversation.version();
        int stored;
        if (expected == 0) {
            stored = jdbc.sql("""
                    INSERT INTO chat_conversations (id, owner, title, created_at, updated_at, version)
                    VALUES (?, ?, ?, ?, ?, 1) ON CONFLICT (id) DO NOTHING""")
                    .params(conversation.id(), conversation.owner().value(), conversation.title(),
                            Timestamp.from(conversation.createdAt()), Timestamp.from(conversation.updatedAt()))
                    .update();
        } else {
            stored = jdbc.sql("""
                    UPDATE chat_conversations SET updated_at = ?, version = version + 1
                     WHERE id = ? AND version = ?""")
                    .params(Timestamp.from(conversation.updatedAt()), conversation.id(), expected).update();
        }
        if (stored == 0) {
            long current = jdbc.sql("SELECT version FROM chat_conversations WHERE id = ?").param(conversation.id())
                    .query(Long.class).optional().orElse(0L);
            throw new StaleVersionException("conversation", conversation.id().toString(), expected, current);
        }
        for (ChatTurn turn : conversation.turns()) {
            jdbc.sql("""
                    INSERT INTO chat_turns (id, conversation_id, seq, question, locale, asked_at, run_id, answer,
                                            answered_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, CAST(? AS jsonb), ?)
                    ON CONFLICT (id) DO UPDATE SET run_id = EXCLUDED.run_id, answer = EXCLUDED.answer,
                                                   answered_at = EXCLUDED.answered_at
                     WHERE chat_turns.conversation_id = EXCLUDED.conversation_id""")
                    .params(turn.id(), conversation.id(), turn.seq(), turn.question(), turn.locale().name(),
                            Timestamp.from(turn.askedAt()), turn.run().value(),
                            turn.answer().map(this::write).orElse(null),
                            turn.answeredAt().map(Timestamp::from).orElse(null))
                    .update();
        }
        conversation.stored(expected + 1);
    }

    @Override
    public void delete(UUID id) {
        jdbc.sql("DELETE FROM chat_conversations WHERE id = ?").param(id).update();
    }

    private record ConversationRow(UUID id, UUID owner, String title, Instant createdAt, Instant updatedAt,
            long version) {
    }

    private static ConversationRow row(ResultSet rs, int index) throws SQLException {
        return new ConversationRow(rs.getObject("id", UUID.class), rs.getObject("owner", UUID.class),
                rs.getString("title"), instant(rs, "created_at"), instant(rs, "updated_at"), rs.getLong("version"));
    }

    private ChatTurn turn(ResultSet rs, int index) throws SQLException {
        String answer = rs.getString("answer_json");
        return ChatTurn.restore(rs.getObject("id", UUID.class), rs.getInt("seq"), rs.getString("question"),
                ProductLocale.valueOf(rs.getString("locale")), instant(rs, "asked_at"),
                new RunId(rs.getObject("run_id", UUID.class)), answer == null ? null : read(answer),
                instant(rs, "answered_at"));
    }

    private String write(ChatAnswer answer) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("text", answer.text());
        value.put("evidence", answer.evidence().name());
        List<Map<String, Object>> citations = new ArrayList<>();
        for (Citation citation : answer.citations()) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("number", citation.number());
            entry.put("kind", citation.kind().name());
            entry.put("sourceId", citation.sourceId().toString());
            entry.put("title", citation.title());
            entry.put("quote", citation.quote());
            entry.put("page", citation.page());
            entry.put("check", citation.check().name());
            citations.add(entry);
        }
        value.put("citations", citations);
        value.put("caveats", answer.caveats());
        return json.writeValueAsString(value);
    }

    private ChatAnswer read(String stored) {
        Map<String, Object> value = json.readValue(stored, OBJECT);
        List<Citation> citations = new ArrayList<>();
        for (Object item : (List<?>) value.get("citations")) {
            Map<?, ?> entry = (Map<?, ?>) item;
            citations.add(new Citation(((Number) entry.get("number")).intValue(),
                    SourceKind.valueOf((String) entry.get("kind")), UUID.fromString((String) entry.get("sourceId")),
                    (String) entry.get("title"), (String) entry.get("quote"),
                    entry.get("page") instanceof Number page ? page.intValue() : null,
                    Check.valueOf((String) entry.get("check"))));
        }
        List<String> caveats = ((List<?>) value.get("caveats")).stream().map(String::valueOf).toList();
        return new ChatAnswer((String) value.get("text"), Evidence.valueOf((String) value.get("evidence")),
                citations, caveats);
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }
}
