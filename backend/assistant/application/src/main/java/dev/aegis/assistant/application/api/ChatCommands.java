package dev.aegis.assistant.application.api;

import dev.aegis.assistant.application.api.ChatViews.ConversationView;
import dev.aegis.shared.Actor;
import java.util.UUID;

/**
 * Questions to the household assistant. The client chooses the conversation and turn ids, so repeating a request
 * after a lost response returns what the first one created instead of asking twice. Each question starts one bounded
 * AI run; nothing waits for the model.
 */
public interface ChatCommands {
    /**
     * Starts a conversation with its first question. {@code locale} ({@code DE} or {@code EN}) is the language to
     * answer in when the question's own language is unclear. {@code family} chooses the model family for this question
     * only; null uses the household's family for questions.
     *
     * @throws dev.aegis.shared.InvalidInputException {@code chat.question.invalid}, {@code ai.notConfigured},
     *         {@code ai.workload.unavailable}, {@code ai.provider.memberNotAllowed}, {@code ai.model.unknownFamily},
     *         {@code ai.model.notOffered} or {@code chat.idConflict} when the id already names other work
     */
    ConversationView start(Actor actor, UUID conversationId, UUID turnId, String question, String locale,
            String family);

    /**
     * Asks a further question once the previous one is answered, failed or cancelled.
     *
     * @throws dev.aegis.shared.InvalidInputException additionally {@code chat.turnActive} and
     *         {@code chat.conversationFull}
     */
    ConversationView ask(Actor actor, UUID conversationId, UUID turnId, String question, String locale,
            String family);

    /** Cancels the run answering a question; a cancelled run never stores an answer. */
    ConversationView cancel(Actor actor, UUID conversationId, UUID turnId);

    /** Asks the latest question again after its run failed or was cancelled, with the model it was asked with. */
    ConversationView retry(Actor actor, UUID conversationId, UUID turnId);

    /** Deletes the conversation; a question still being answered is cancelled first. */
    void delete(Actor actor, UUID conversationId);
}
