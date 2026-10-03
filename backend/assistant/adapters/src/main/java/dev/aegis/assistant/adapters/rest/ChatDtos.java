package dev.aegis.assistant.adapters.rest;

import dev.aegis.assistant.application.api.ChatViews.AnswerView;
import dev.aegis.assistant.application.api.ChatViews.ChatOverview;
import dev.aegis.assistant.application.api.ChatViews.CitationView;
import dev.aegis.assistant.application.api.ChatViews.ConversationSummary;
import dev.aegis.assistant.application.api.ChatViews.ConversationView;
import dev.aegis.assistant.application.api.ChatViews.ProgressView;
import dev.aegis.assistant.application.api.ChatViews.TurnView;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** REST representations of conversations with the household assistant. Codes are stable English values. */
public final class ChatDtos {
    private ChatDtos() {
    }

    @Schema(description = "A question; the client chooses the ids so that a repeated request does not ask twice")
    public record AskQuestionRequest(UUID conversationId, UUID turnId, String question,
            @Schema(allowableValues = {"DE", "EN"}, description = "Answer language when the question's is unclear")
            String locale) {
    }

    public record ChatOverviewDto(boolean available,
            @Schema(nullable = true, description = "Why a question would be refused now, for example ai.notConfigured")
            String unavailableReason, List<ConversationSummaryDto> conversations) {
        static ChatOverviewDto from(ChatOverview overview) {
            return new ChatOverviewDto(overview.available(), overview.unavailableReason(),
                    overview.conversations().stream().map(ConversationSummaryDto::from).toList());
        }
    }

    public record ConversationSearchDto(List<ConversationSummaryDto> conversations) {
        static ConversationSearchDto from(List<ConversationSummary> found) {
            return new ConversationSearchDto(found.stream().map(ConversationSummaryDto::from).toList());
        }
    }

    public record ConversationSummaryDto(UUID id, String title, Instant createdAt, Instant updatedAt,
            int questions) {
        static ConversationSummaryDto from(ConversationSummary summary) {
            return new ConversationSummaryDto(summary.id(), summary.title(), summary.createdAt(), summary.updatedAt(),
                    summary.questions());
        }
    }

    public record ConversationDto(UUID id, String title, Instant createdAt, Instant updatedAt,
            List<ChatTurnDto> turns) {
        static ConversationDto from(ConversationView view) {
            return new ConversationDto(view.id(), view.title(), view.createdAt(), view.updatedAt(),
                    view.turns().stream().map(ChatTurnDto::from).toList());
        }
    }

    public record ChatTurnDto(UUID id, int seq, String question,
            @Schema(allowableValues = {"DE", "EN"}) String locale, Instant askedAt, UUID runId,
            @Schema(allowableValues = {"WAITING", "WORKING", "ANSWERED", "FAILED", "CANCELLED"}) String state,
            @Schema(nullable = true) Instant nextAttemptAt, @Schema(nullable = true) String failureCategory,
            @Schema(nullable = true) String failureCode, List<ChatProgressDto> progress,
            @Schema(nullable = true) ChatAnswerDto answer, @Schema(nullable = true) Instant answeredAt) {
        static ChatTurnDto from(TurnView turn) {
            return new ChatTurnDto(turn.id(), turn.seq(), turn.question(), turn.locale(), turn.askedAt(),
                    turn.runId(), turn.state(), turn.nextAttemptAt(), turn.failureCategory(), turn.failureCode(),
                    turn.progress().stream().map(ChatProgressDto::from).toList(),
                    turn.answer() == null ? null : ChatAnswerDto.from(turn.answer()), turn.answeredAt());
        }
    }

    public record ChatProgressDto(@Schema(allowableValues = {"ATTEMPT", "MODEL", "TOOL"}) String kind,
            @Schema(nullable = true) String tool, Instant at) {
        static ChatProgressDto from(ProgressView progress) {
            return new ChatProgressDto(progress.kind(), progress.tool(), progress.at());
        }
    }

    public record ChatAnswerDto(String text,
            @Schema(allowableValues = {"COMPLETE", "PARTIAL", "NOT_FOUND"}) String evidence,
            List<ChatCitationDto> citations, List<String> caveats) {
        static ChatAnswerDto from(AnswerView answer) {
            return new ChatAnswerDto(answer.text(), answer.evidence(),
                    answer.citations().stream().map(ChatCitationDto::from).toList(), answer.caveats());
        }
    }

    public record ChatCitationDto(int number,
            @Schema(allowableValues = {"DOCUMENT", "CONTRACT", "TASK"}) String kind, UUID sourceId,
            @Schema(nullable = true) String title, @Schema(nullable = true) String quote,
            @Schema(nullable = true) Integer page,
            @Schema(allowableValues = {"VERIFIED", "NOT_IN_SOURCE", "SOURCE_UNAVAILABLE"}) String check) {
        static ChatCitationDto from(CitationView citation) {
            return new ChatCitationDto(citation.number(), citation.kind(), citation.sourceId(), citation.title(),
                    citation.quote(), citation.page(), citation.check());
        }
    }
}
