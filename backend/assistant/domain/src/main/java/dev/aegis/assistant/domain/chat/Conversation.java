package dev.aegis.assistant.domain.chat;

import dev.aegis.assistant.domain.RunId;
import dev.aegis.shared.InvalidInputException;
import dev.aegis.shared.MemberId;
import dev.aegis.shared.ProductLocale;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * A member's conversation with the household assistant: questions in order, each answered by one bounded AI run. A
 * conversation belongs to the member who started it; only that member reads or continues it. One question is open at
 * a time, so every answer can build on the answers before it.
 */
public final class Conversation {
    public static final int MAX_QUESTION = 2_000;
    public static final int MAX_TURNS = 100;
    static final int MAX_TITLE = 80;

    private final UUID id;
    private final MemberId owner;
    private final Instant createdAt;
    private final String title;
    private final List<ChatTurn> turns;
    private Instant updatedAt;
    private long version;

    private Conversation(UUID id, MemberId owner, Instant createdAt, String title, List<ChatTurn> turns,
            Instant updatedAt, long version) {
        this.id = Objects.requireNonNull(id, "conversation id");
        this.owner = Objects.requireNonNull(owner, "owner");
        this.createdAt = Objects.requireNonNull(createdAt, "created at");
        this.title = Objects.requireNonNull(title, "title");
        this.turns = new ArrayList<>(turns);
        this.updatedAt = Objects.requireNonNull(updatedAt, "updated at");
        this.version = version;
    }

    /** Starts a conversation with its first question; its title is the beginning of that question. */
    public static Conversation start(UUID id, MemberId owner, UUID turnId, String question, ProductLocale locale,
            RunId run, Instant at) {
        String asked = checkedQuestion(question);
        Conversation conversation = new Conversation(id, owner, at, titleOf(asked), List.of(), at, 0);
        conversation.turns.add(new ChatTurn(turnId, 1, asked, locale, at, run, null, null));
        return conversation;
    }

    public static Conversation restore(UUID id, MemberId owner, Instant createdAt, String title, List<ChatTurn> turns,
            Instant updatedAt, long version) {
        return new Conversation(id, owner, createdAt, title,
                turns.stream().sorted((a, b) -> Integer.compare(a.seq(), b.seq())).toList(), updatedAt, version);
    }

    /**
     * Normalizes a question: surrounding blanks removed, 1 to {@link #MAX_QUESTION} characters.
     *
     * @throws InvalidInputException {@code chat.question.invalid}
     */
    public static String checkedQuestion(String question) {
        String asked = question == null ? "" : question.strip();
        if (asked.isEmpty() || asked.length() > MAX_QUESTION) {
            throw new InvalidInputException("chat.question.invalid",
                    "A question needs 1 to " + MAX_QUESTION + " characters");
        }
        return asked;
    }

    /**
     * Adds a question answered by {@code run}. {@code lastRunSettled} says whether the run of the latest question has
     * finished (answered, failed or cancelled); the execution state belongs to the run, not to the conversation.
     *
     * @throws InvalidInputException {@code chat.turnActive} while the latest question is still being answered,
     *         {@code chat.conversationFull} after {@link #MAX_TURNS} questions
     */
    public ChatTurn ask(UUID turnId, String question, ProductLocale locale, RunId run, Instant at,
            boolean lastRunSettled) {
        String asked = checkedQuestion(question);
        if (turn(turnId).isPresent()) {
            throw new IllegalArgumentException("turn " + turnId + " already exists");
        }
        if (!lastRunSettled && latest().answer().isEmpty()) {
            throw new InvalidInputException("chat.turnActive", "The previous question is still being answered");
        }
        if (turns.size() >= MAX_TURNS) {
            throw new InvalidInputException("chat.conversationFull",
                    "A conversation holds at most " + MAX_TURNS + " questions");
        }
        ChatTurn turn = new ChatTurn(turnId, latest().seq() + 1, asked, locale, at, run, null, null);
        turns.add(turn);
        updatedAt = at;
        return turn;
    }

    /**
     * Asks the latest question again with a new run after its run failed or was cancelled.
     *
     * @throws InvalidInputException {@code chat.turn.notRetryable} for an earlier, answered or still running question
     */
    public void retry(UUID turnId, RunId run, Instant at, boolean currentRunSettled) {
        ChatTurn turn = turn(turnId).orElseThrow(() -> new IllegalArgumentException("unknown turn " + turnId));
        if (turn != latest() || turn.answer().isPresent() || !currentRunSettled) {
            throw new InvalidInputException("chat.turn.notRetryable",
                    "Only the latest unanswered question can be asked again after its run ended");
        }
        turn.rerun(run);
        updatedAt = at;
    }

    /**
     * Records the answer of {@code run}. Only the turn's current run answers, and only once; anything else is ignored
     * and reported as false, so a late or repeated completion changes nothing.
     */
    public boolean answer(UUID turnId, RunId run, ChatAnswer answer, Instant at) {
        boolean answered = turn(turnId).map(turn -> turn.answerWith(run, answer, at)).orElse(false);
        if (answered) {
            updatedAt = at;
        }
        return answered;
    }

    public Optional<ChatTurn> turn(UUID turnId) {
        return turns.stream().filter(turn -> turn.id().equals(turnId)).findFirst();
    }

    public ChatTurn latest() {
        return turns.getLast();
    }

    public boolean ownedBy(MemberId member) {
        return owner.equals(member);
    }

    /** The version this conversation was loaded with; the repository checks it when saving. */
    public long version() {
        return version;
    }

    public void stored(long newVersion) {
        version = newVersion;
    }

    public UUID id() {
        return id;
    }

    public MemberId owner() {
        return owner;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public Instant updatedAt() {
        return updatedAt;
    }

    public String title() {
        return title;
    }

    public List<ChatTurn> turns() {
        return List.copyOf(turns);
    }

    private static String titleOf(String question) {
        String line = question.replaceAll("\\s+", " ");
        return line.length() <= MAX_TITLE ? line : line.substring(0, MAX_TITLE - 1).stripTrailing() + "…";
    }
}
