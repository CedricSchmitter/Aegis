package dev.aegis.assistant.application.chat;

import dev.aegis.assistant.domain.chat.ChatTurn;
import dev.aegis.assistant.domain.chat.Conversation;
import dev.aegis.assistant.domain.chat.ConversationRepository;
import dev.aegis.shared.MemberId;
import dev.aegis.shared.StaleVersionException;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Stores copies, like a database, so a test sees only what was saved. */
class InMemoryConversations implements ConversationRepository {
    private final Map<UUID, Conversation> stored = new ConcurrentHashMap<>();
    int saves;

    @Override
    public Optional<Conversation> findById(UUID id) {
        return Optional.ofNullable(stored.get(id)).map(InMemoryConversations::copy);
    }

    @Override
    public List<ConversationHeader> findByOwner(MemberId owner, int limit) {
        return stored.values().stream().filter(conversation -> conversation.ownedBy(owner))
                .sorted(Comparator.comparing(Conversation::updatedAt).reversed()).limit(limit)
                .map(conversation -> new ConversationHeader(conversation.id(), conversation.title(),
                        conversation.createdAt(), conversation.updatedAt(), conversation.turns().size()))
                .toList();
    }

    @Override
    public void save(Conversation conversation) {
        long current = Optional.ofNullable(stored.get(conversation.id())).map(Conversation::version).orElse(0L);
        if (current != conversation.version()) {
            throw new StaleVersionException("conversation", conversation.id().toString(), conversation.version(),
                    current);
        }
        conversation.stored(current + 1);
        stored.put(conversation.id(), copy(conversation));
        saves++;
    }

    @Override
    public void delete(UUID id) {
        stored.remove(id);
    }

    private static Conversation copy(Conversation conversation) {
        List<ChatTurn> turns = conversation.turns().stream()
                .map(turn -> ChatTurn.restore(turn.id(), turn.seq(), turn.question(), turn.locale(), turn.askedAt(),
                        turn.run(), turn.answer().orElse(null), turn.answeredAt().orElse(null)))
                .toList();
        return Conversation.restore(conversation.id(), conversation.owner(), conversation.createdAt(),
                conversation.title(), turns, conversation.updatedAt(), conversation.version());
    }
}
