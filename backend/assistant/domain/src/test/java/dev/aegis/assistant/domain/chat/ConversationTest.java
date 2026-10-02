package dev.aegis.assistant.domain.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.aegis.assistant.domain.RunId;
import dev.aegis.assistant.domain.chat.ChatAnswer.Evidence;
import dev.aegis.shared.InvalidInputException;
import dev.aegis.shared.MemberId;
import dev.aegis.shared.ProductLocale;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ConversationTest {
    static final Instant T0 = Instant.parse("2026-10-02T08:00:00Z");
    static final ChatAnswer ANSWER = new ChatAnswer("Am 31.01.2027 [1].", Evidence.COMPLETE, List.of(), List.of());

    private final MemberId owner = MemberId.random();
    private final UUID first = UUID.randomUUID();
    private final RunId firstRun = RunId.random();

    private Conversation started() {
        return Conversation.start(UUID.randomUUID(), owner, first, "  Wann endet der Stromvertrag?  ",
                ProductLocale.DE, firstRun, T0);
    }

    @Test
    void theFirstQuestionStartsTheConversationAndNamesIt() {
        Conversation conversation = started();

        assertThat(conversation.title()).isEqualTo("Wann endet der Stromvertrag?");
        assertThat(conversation.latest().question()).isEqualTo("Wann endet der Stromvertrag?");
        assertThat(conversation.latest().seq()).isEqualTo(1);
        assertThat(conversation.ownedBy(owner)).isTrue();
        assertThat(conversation.ownedBy(MemberId.random())).isFalse();
    }

    @Test
    void aLongFirstQuestionGivesAShortenedTitle() {
        Conversation conversation = Conversation.start(UUID.randomUUID(), owner, first, "x".repeat(300),
                ProductLocale.EN, firstRun, T0);

        assertThat(conversation.title()).hasSize(Conversation.MAX_TITLE).endsWith("…");
    }

    @Test
    void questionsNeedText() {
        assertThatThrownBy(() -> Conversation.checkedQuestion("   ")).isInstanceOfSatisfying(
                InvalidInputException.class, invalid -> assertThat(invalid.code()).isEqualTo("chat.question.invalid"));
        assertThatThrownBy(() -> Conversation.checkedQuestion("x".repeat(Conversation.MAX_QUESTION + 1)))
                .isInstanceOf(InvalidInputException.class);
    }

    @Test
    void aFurtherQuestionWaitsUntilThePreviousOneIsAnsweredOrItsRunEnded() {
        Conversation conversation = started();

        assertThatThrownBy(() -> conversation.ask(UUID.randomUUID(), "Und der Gasvertrag?", ProductLocale.DE,
                RunId.random(), T0.plusSeconds(5), false)).isInstanceOfSatisfying(InvalidInputException.class,
                        invalid -> assertThat(invalid.code()).isEqualTo("chat.turnActive"));

        conversation.ask(UUID.randomUUID(), "Und der Gasvertrag?", ProductLocale.DE, RunId.random(),
                T0.plusSeconds(5), true);
        assertThat(conversation.turns()).extracting(ChatTurn::seq).containsExactly(1, 2);
        assertThat(conversation.updatedAt()).isEqualTo(T0.plusSeconds(5));
    }

    @Test
    void anAnsweredQuestionAllowsTheNextOneEvenBeforeItsRunIsSeenAsEnded() {
        Conversation conversation = started();
        conversation.answer(first, firstRun, ANSWER, T0.plusSeconds(20));

        conversation.ask(UUID.randomUUID(), "Und der Gasvertrag?", ProductLocale.DE, RunId.random(),
                T0.plusSeconds(30), false);

        assertThat(conversation.turns()).hasSize(2);
    }

    @Test
    void onlyTheCurrentRunAnswersAndOnlyOnce() {
        Conversation conversation = started();

        assertThat(conversation.answer(first, RunId.random(), ANSWER, T0.plusSeconds(10))).isFalse();
        assertThat(conversation.answer(first, firstRun, ANSWER, T0.plusSeconds(10))).isTrue();
        assertThat(conversation.answer(first, firstRun, ANSWER, T0.plusSeconds(11))).isFalse();
        assertThat(conversation.latest().answer()).contains(ANSWER);
        assertThat(conversation.latest().answeredAt()).contains(T0.plusSeconds(10));
    }

    @Test
    void aRetryReplacesTheRunOfTheLatestUnansweredQuestionAfterItEnded() {
        Conversation conversation = started();
        RunId second = RunId.random();

        assertThatThrownBy(() -> conversation.retry(first, second, T0.plusSeconds(5), false))
                .isInstanceOfSatisfying(InvalidInputException.class,
                        invalid -> assertThat(invalid.code()).isEqualTo("chat.turn.notRetryable"));
        conversation.retry(first, second, T0.plusSeconds(5), true);

        assertThat(conversation.latest().run()).isEqualTo(second);
        assertThat(conversation.answer(first, firstRun, ANSWER, T0.plusSeconds(6))).as("superseded run").isFalse();
        assertThat(conversation.answer(first, second, ANSWER, T0.plusSeconds(6))).isTrue();
        assertThatThrownBy(() -> conversation.retry(first, RunId.random(), T0.plusSeconds(7), true))
                .as("answered").isInstanceOf(InvalidInputException.class);
    }

    @Test
    void aConversationHoldsABoundedNumberOfQuestions() {
        Conversation conversation = started();
        for (int i = 2; i <= Conversation.MAX_TURNS; i++) {
            conversation.ask(UUID.randomUUID(), "Frage " + i, ProductLocale.DE, RunId.random(), T0, true);
        }

        assertThatThrownBy(() -> conversation.ask(UUID.randomUUID(), "Noch eine", ProductLocale.DE, RunId.random(),
                T0, true)).isInstanceOfSatisfying(InvalidInputException.class,
                        invalid -> assertThat(invalid.code()).isEqualTo("chat.conversationFull"));
    }
}
