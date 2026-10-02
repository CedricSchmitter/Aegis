package dev.aegis.assistant.domain.chat;

import dev.aegis.assistant.domain.RunId;
import dev.aegis.shared.ProductLocale;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * One question of a conversation and the run that answers it. The client chooses {@code id}, so a repeated request
 * finds the turn it already created. {@code run} is the latest run for the question; a retry replaces it. The answer
 * is set once, by the run that is current when it completes.
 */
public final class ChatTurn {
    private final UUID id;
    private final int seq;
    private final String question;
    private final ProductLocale locale;
    private final Instant askedAt;
    private RunId run;
    private ChatAnswer answer;
    private Instant answeredAt;

    ChatTurn(UUID id, int seq, String question, ProductLocale locale, Instant askedAt, RunId run, ChatAnswer answer,
            Instant answeredAt) {
        this.id = Objects.requireNonNull(id, "turn id");
        this.question = Objects.requireNonNull(question, "question");
        this.locale = Objects.requireNonNull(locale, "locale");
        this.askedAt = Objects.requireNonNull(askedAt, "asked at");
        this.run = Objects.requireNonNull(run, "run");
        if (seq < 1) {
            throw new IllegalArgumentException("turns are numbered from 1");
        }
        if ((answer == null) != (answeredAt == null)) {
            throw new IllegalArgumentException("an answer needs its time");
        }
        this.seq = seq;
        this.answer = answer;
        this.answeredAt = answeredAt;
    }

    public static ChatTurn restore(UUID id, int seq, String question, ProductLocale locale, Instant askedAt, RunId run,
            ChatAnswer answer, Instant answeredAt) {
        return new ChatTurn(id, seq, question, locale, askedAt, run, answer, answeredAt);
    }

    boolean answerWith(RunId by, ChatAnswer value, Instant at) {
        if (answer != null || !run.equals(by)) {
            return false;
        }
        answer = Objects.requireNonNull(value, "answer");
        answeredAt = Objects.requireNonNull(at, "answered at");
        return true;
    }

    void rerun(RunId next) {
        run = Objects.requireNonNull(next, "run");
    }

    public UUID id() {
        return id;
    }

    public int seq() {
        return seq;
    }

    public String question() {
        return question;
    }

    public ProductLocale locale() {
        return locale;
    }

    public Instant askedAt() {
        return askedAt;
    }

    public RunId run() {
        return run;
    }

    public Optional<ChatAnswer> answer() {
        return Optional.ofNullable(answer);
    }

    public Optional<Instant> answeredAt() {
        return Optional.ofNullable(answeredAt);
    }
}
