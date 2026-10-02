package dev.aegis.assistant.application.api;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Read models of the household assistant's conversations. Codes are stable English values the UI localizes. */
public final class ChatViews {
    private ChatViews() {
    }

    /**
     * Whether the member can ask now and their conversations, newest first. {@code unavailableReason} is the code a
     * question would be refused with ({@code ai.notConfigured}, {@code ai.provider.memberNotAllowed}, ...), otherwise
     * null.
     */
    public record ChatOverview(boolean available, String unavailableReason, List<ConversationSummary> conversations) {
        public ChatOverview {
            conversations = List.copyOf(conversations);
        }
    }

    public record ConversationSummary(UUID id, String title, Instant createdAt, Instant updatedAt, int questions) {
    }

    public record ConversationView(UUID id, String title, Instant createdAt, Instant updatedAt,
            List<TurnView> turns) {
        public ConversationView {
            turns = List.copyOf(turns);
        }
    }

    /**
     * One question. {@code state}: {@code WAITING} (queued, waiting for a free slot, a retry or the next budget day
     * at {@code nextAttemptAt}), {@code WORKING}, {@code ANSWERED}, {@code FAILED} ({@code failureCategory} and
     * {@code failureCode} say why) or {@code CANCELLED}. {@code progress} lists what the latest question's run has
     * done so far; it is empty for earlier questions.
     */
    public record TurnView(UUID id, int seq, String question, String locale, Instant askedAt, UUID runId,
            String state, Instant nextAttemptAt, String failureCategory, String failureCode,
            List<ProgressView> progress, AnswerView answer, Instant answeredAt) {
        public TurnView {
            progress = List.copyOf(progress);
        }
    }

    /**
     * A visible step: {@code kind} {@code ATTEMPT} (an attempt started), {@code MODEL} (the model was asked) or
     * {@code TOOL} with the tool's name in {@code tool}. Never contains arguments or content.
     */
    public record ProgressView(String kind, String tool, Instant at) {
    }

    /** {@code evidence}: {@code COMPLETE}, {@code PARTIAL} or {@code NOT_FOUND}. */
    public record AnswerView(String text, String evidence, List<CitationView> citations, List<String> caveats) {
        public AnswerView {
            citations = List.copyOf(citations);
            caveats = List.copyOf(caveats);
        }
    }

    /**
     * {@code kind}: {@code DOCUMENT}, {@code CONTRACT} or {@code TASK}. {@code check}: {@code VERIFIED},
     * {@code NOT_IN_SOURCE} (the quote is not in the document) or {@code SOURCE_UNAVAILABLE}.
     */
    public record CitationView(int number, String kind, UUID sourceId, String title, String quote, Integer page,
            String check) {
    }
}
