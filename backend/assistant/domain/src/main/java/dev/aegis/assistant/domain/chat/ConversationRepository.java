package dev.aegis.assistant.domain.chat;

import dev.aegis.shared.MemberId;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Persistence of conversations with their turns. */
public interface ConversationRepository {
    /** A list entry: enough to recognize and order a conversation. */
    record ConversationHeader(UUID id, String title, Instant createdAt, Instant updatedAt, int turns) {
    }

    Optional<Conversation> findById(UUID id);

    /** The member's conversations, most recently updated first. */
    List<ConversationHeader> findByOwner(MemberId owner, int limit);

    /**
     * Inserts a new conversation (version 0) or updates an existing one if its stored version still matches, and
     * stores its turns; the conversation then carries the new version.
     *
     * @throws dev.aegis.shared.StaleVersionException when another transaction changed the conversation meanwhile
     */
    void save(Conversation conversation);

    void delete(UUID id);
}
