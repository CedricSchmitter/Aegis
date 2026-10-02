package dev.aegis.assistant.application.chat;

import dev.aegis.assistant.application.api.AiRunCommands;
import dev.aegis.assistant.application.api.AiRunQueries;
import dev.aegis.assistant.application.api.ChatCommands;
import dev.aegis.assistant.application.api.ChatQueries;
import dev.aegis.assistant.application.api.ChatViews.AnswerView;
import dev.aegis.assistant.application.api.ChatViews.ChatOverview;
import dev.aegis.assistant.application.api.ChatViews.CitationView;
import dev.aegis.assistant.application.api.ChatViews.ConversationSummary;
import dev.aegis.assistant.application.api.ChatViews.ConversationView;
import dev.aegis.assistant.application.api.ChatViews.ProgressView;
import dev.aegis.assistant.application.api.ChatViews.TurnView;
import dev.aegis.assistant.application.api.RequestAiRun;
import dev.aegis.assistant.application.api.RunRequest;
import dev.aegis.assistant.application.api.RunView;
import dev.aegis.assistant.application.api.WorkloadEstimate;
import dev.aegis.assistant.domain.AiRun;
import dev.aegis.assistant.domain.AiRunRepository;
import dev.aegis.assistant.domain.RunFailure;
import dev.aegis.assistant.domain.RunId;
import dev.aegis.assistant.domain.RunStep;
import dev.aegis.assistant.domain.Workload;
import dev.aegis.assistant.domain.chat.ChatAnswer;
import dev.aegis.assistant.domain.chat.ChatTurn;
import dev.aegis.assistant.domain.chat.Conversation;
import dev.aegis.assistant.domain.chat.ConversationRepository;
import dev.aegis.shared.Actor;
import dev.aegis.shared.HouseholdAccess;
import dev.aegis.shared.InvalidInputException;
import dev.aegis.shared.MemberId;
import dev.aegis.shared.NotFoundException;
import dev.aegis.shared.ProductLocale;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Conversations with the household assistant (issue #25). A question and the run that answers it are stored in one
 * transaction, so a question is never left without its run. The run's state is the source of truth for how far the
 * answer is; the conversation only receives the finished answer.
 */
public class ChatService implements ChatCommands, ChatQueries {
    static final int MAX_CONVERSATIONS = 50;
    static final int MAX_PROGRESS = 40;

    private final ConversationRepository conversations;
    private final AiRunRepository runs;
    private final RequestAiRun requests;
    private final AiRunCommands runCommands;
    private final AiRunQueries runQueries;
    private final HouseholdAccess access;
    private final Clock clock;

    public ChatService(ConversationRepository conversations, AiRunRepository runs, RequestAiRun requests,
            AiRunCommands runCommands, AiRunQueries runQueries, HouseholdAccess access, Clock clock) {
        this.conversations = conversations;
        this.runs = runs;
        this.requests = requests;
        this.runCommands = runCommands;
        this.runQueries = runQueries;
        this.access = access;
        this.clock = clock;
    }

    @Override
    public ChatOverview overview(Actor actor) {
        access.requireMember(actor);
        WorkloadEstimate estimate = runQueries.estimate(actor, Workload.CHAT.name());
        List<ConversationSummary> list = conversations.findByOwner(actor.memberId(), MAX_CONVERSATIONS).stream()
                .map(header -> new ConversationSummary(header.id(), header.title(), header.createdAt(),
                        header.updatedAt(), header.turns()))
                .toList();
        return new ChatOverview(estimate.refusal() == null, estimate.refusal(), list);
    }

    @Override
    public ConversationView get(Actor actor, UUID conversationId) {
        access.requireMember(actor);
        return view(owned(actor, conversationId));
    }

    @Override
    public ConversationView start(Actor actor, UUID conversationId, UUID turnId, String question, String locale) {
        access.requireMember(actor);
        requireIds(conversationId, turnId);
        String asked = Conversation.checkedQuestion(question);
        Optional<Conversation> existing = conversations.findById(conversationId);
        if (existing.isPresent()) {
            Conversation conversation = existing.get();
            boolean repeated = conversation.ownedBy(actor.memberId()) && conversation.turns().getFirst().id()
                    .equals(turnId) && conversation.turns().getFirst().question().equals(asked);
            if (!repeated) {
                throw idConflict();
            }
            return view(conversation);
        }
        RunId run = requestRun(actor, conversationId, turnId);
        Conversation conversation = Conversation.start(conversationId, actor.memberId(), turnId, asked,
                locale(locale), run, clock.instant());
        conversations.save(conversation);
        return view(conversation);
    }

    @Override
    public ConversationView ask(Actor actor, UUID conversationId, UUID turnId, String question, String locale) {
        access.requireMember(actor);
        requireIds(conversationId, turnId);
        String asked = Conversation.checkedQuestion(question);
        Conversation conversation = owned(actor, conversationId);
        Optional<ChatTurn> existing = conversation.turn(turnId);
        if (existing.isPresent()) {
            if (!existing.get().question().equals(asked)) {
                throw idConflict();
            }
            return view(conversation);
        }
        ChatTurn latest = conversation.latest();
        boolean settled = settled(latest.run());
        if (!settled && latest.answer().isEmpty()) {
            throw new InvalidInputException("chat.turnActive", "The previous question is still being answered");
        }
        RunId run = requestRun(actor, conversationId, turnId);
        conversation.ask(turnId, asked, locale(locale), run, clock.instant(), settled);
        conversations.save(conversation);
        return view(conversation);
    }

    @Override
    public ConversationView cancel(Actor actor, UUID conversationId, UUID turnId) {
        access.requireMember(actor);
        Conversation conversation = owned(actor, conversationId);
        ChatTurn turn = turn(conversation, turnId);
        if (turn.answer().isEmpty() && !settled(turn.run())) {
            runCommands.cancel(actor, turn.run().value());
        }
        return view(conversation);
    }

    @Override
    public ConversationView retry(Actor actor, UUID conversationId, UUID turnId) {
        access.requireMember(actor);
        Conversation conversation = owned(actor, conversationId);
        ChatTurn turn = turn(conversation, turnId);
        boolean settled = settled(turn.run());
        if (turn != conversation.latest() || turn.answer().isPresent() || !settled) {
            throw new InvalidInputException("chat.turn.notRetryable",
                    "Only the latest unanswered question can be asked again after its run ended");
        }
        RunView next = runCommands.retry(actor, turn.run().value());
        conversation.retry(turnId, new RunId(next.id()), clock.instant(), true);
        conversations.save(conversation);
        return view(conversation);
    }

    @Override
    public void delete(Actor actor, UUID conversationId) {
        access.requireMember(actor);
        Conversation conversation = owned(actor, conversationId);
        ChatTurn latest = conversation.latest();
        if (latest.answer().isEmpty() && !settled(latest.run())) {
            runCommands.cancel(actor, latest.run().value());
        }
        conversations.delete(conversationId);
    }

    private RunId requestRun(Actor actor, UUID conversationId, UUID turnId) {
        RunView run = requests.request(actor, new RunRequest(Workload.CHAT.name(), "chat:" + turnId, List.of(),
                Map.of(ChatHandler.CONVERSATION, conversationId.toString(), ChatHandler.TURN, turnId.toString()),
                false));
        return new RunId(run.id());
    }

    private boolean settled(RunId run) {
        return runs.findById(run).map(found -> found.state().terminal()).orElse(true);
    }

    /** Another member's conversation is reported as missing, so its existence is not revealed. */
    private Conversation owned(Actor actor, UUID conversationId) {
        MemberId member = actor.memberId();
        return conversations.findById(conversationId).filter(conversation -> conversation.ownedBy(member))
                .orElseThrow(() -> new NotFoundException("conversation", String.valueOf(conversationId)));
    }

    private static ChatTurn turn(Conversation conversation, UUID turnId) {
        return conversation.turn(turnId)
                .orElseThrow(() -> new NotFoundException("chatTurn", String.valueOf(turnId)));
    }

    private static void requireIds(UUID conversationId, UUID turnId) {
        if (conversationId == null || turnId == null) {
            throw new InvalidInputException("validation.failed", "Conversation and question need ids");
        }
    }

    private static InvalidInputException idConflict() {
        return new InvalidInputException("chat.idConflict", "The id already names another question");
    }

    private static ProductLocale locale(String locale) {
        try {
            return locale == null ? ProductLocale.defaultLocale()
                    : ProductLocale.valueOf(locale.strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException unknown) {
            return ProductLocale.defaultLocale();
        }
    }

    private ConversationView view(Conversation conversation) {
        List<TurnView> turns = new ArrayList<>();
        ChatTurn latest = conversation.latest();
        for (ChatTurn turn : conversation.turns()) {
            turns.add(turnView(turn, turn == latest));
        }
        return new ConversationView(conversation.id(), conversation.title(), conversation.createdAt(),
                conversation.updatedAt(), turns);
    }

    private TurnView turnView(ChatTurn turn, boolean latest) {
        List<ProgressView> progress = latest ? progress(turn.run()) : List.of();
        if (turn.answer().isPresent()) {
            return new TurnView(turn.id(), turn.seq(), turn.question(), turn.locale().name(), turn.askedAt(),
                    turn.run().value(), "ANSWERED", null, null, null, progress, answerView(turn.answer().get()),
                    turn.answeredAt().orElse(null));
        }
        Optional<AiRun> run = runs.findById(turn.run());
        String state = run.map(ChatService::stateOf).orElse("FAILED");
        boolean waiting = "WAITING".equals(state);
        Instant next = waiting ? run.flatMap(AiRun::nextAttemptAt).orElse(null) : null;
        Optional<RunFailure> failure = waiting || "FAILED".equals(state) ? run.flatMap(AiRun::failure)
                : Optional.empty();
        String category = failure.map(found -> found.category().name()).orElse(null);
        String code = failure.map(RunFailure::code).orElse("FAILED".equals(state) ? "chat.answerMissing" : null);
        return new TurnView(turn.id(), turn.seq(), turn.question(), turn.locale().name(), turn.askedAt(),
                turn.run().value(), state, next, category, code, progress, null, null);
    }

    /** A run that succeeded without storing an answer counts as failed: the member sees no answer. */
    private static String stateOf(AiRun run) {
        return switch (run.state()) {
            case QUEUED, WAITING_RETRY -> "WAITING";
            case RUNNING -> "WORKING";
            case SUCCEEDED, FAILED -> "FAILED";
            case CANCELLED -> "CANCELLED";
        };
    }

    private List<ProgressView> progress(RunId run) {
        List<ProgressView> progress = new ArrayList<>();
        for (RunStep step : runs.steps(run)) {
            switch (step.kind()) {
                case ATTEMPT_STARTED -> progress.add(new ProgressView("ATTEMPT", null, step.at()));
                case MODEL_CALL -> progress.add(new ProgressView("MODEL", null, step.at()));
                case TOOL_CALL -> progress.add(new ProgressView("TOOL", step.name(), step.at()));
                default -> {
                }
            }
        }
        return progress.size() <= MAX_PROGRESS ? progress
                : progress.subList(progress.size() - MAX_PROGRESS, progress.size());
    }

    private static AnswerView answerView(ChatAnswer answer) {
        return new AnswerView(answer.text(), answer.evidence().name(), answer.citations().stream()
                .map(citation -> new CitationView(citation.number(), citation.kind().name(), citation.sourceId(),
                        citation.title(), citation.quote(), citation.page(), citation.check().name()))
                .toList(), answer.caveats());
    }
}
