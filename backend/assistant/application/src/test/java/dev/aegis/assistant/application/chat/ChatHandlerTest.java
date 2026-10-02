package dev.aegis.assistant.application.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.aegis.assistant.application.InvalidModelOutputException;
import dev.aegis.assistant.application.PreparedRun;
import dev.aegis.assistant.application.RunContext;
import dev.aegis.assistant.application.ValidatedOutput;
import dev.aegis.assistant.application.ports.ContentPart;
import dev.aegis.assistant.application.ports.ModelMessage;
import dev.aegis.assistant.application.ports.ModelTurn;
import dev.aegis.assistant.domain.ModelSelection;
import dev.aegis.assistant.domain.RunId;
import dev.aegis.assistant.domain.TokenUsage;
import dev.aegis.assistant.domain.Workload;
import dev.aegis.assistant.domain.chat.ChatAnswer;
import dev.aegis.assistant.domain.chat.ChatAnswer.Check;
import dev.aegis.assistant.domain.chat.ChatAnswer.Citation;
import dev.aegis.assistant.domain.chat.ChatAnswer.Evidence;
import dev.aegis.assistant.domain.chat.ChatAnswer.SourceKind;
import dev.aegis.assistant.domain.chat.Conversation;
import dev.aegis.contracts.application.api.ContractQueries;
import dev.aegis.contracts.application.api.ContractViews.ContractSummary;
import dev.aegis.contracts.application.api.ContractViews.ContractView;
import dev.aegis.documents.application.api.DocumentTextQuery;
import dev.aegis.planning.application.api.TaskQueries;
import dev.aegis.planning.application.api.TaskSummary;
import dev.aegis.shared.Actor;
import dev.aegis.shared.ContractId;
import dev.aegis.shared.DocumentId;
import dev.aegis.shared.MemberId;
import dev.aegis.shared.NotAuthorizedException;
import dev.aegis.shared.NotFoundException;
import dev.aegis.shared.ProductLocale;
import dev.aegis.shared.TaskId;
import dev.aegis.shared.Trash;
import dev.aegis.shared.TrashedException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ChatHandlerTest {
    static final Instant NOW = Instant.parse("2026-10-02T22:30:00Z");
    static final ZoneId BERLIN = ZoneId.of("Europe/Berlin");
    static final MemberId LISA = MemberId.random();
    static final UUID CONTRACT_DOC = UUID.randomUUID();
    static final UUID TRASHED_DOC = UUID.randomUUID();
    static final UUID CONTRACT = UUID.randomUUID();
    static final UUID OPEN_TASK = UUID.randomUUID();
    static final UUID TRASHED_TASK = UUID.randomUUID();
    static final String CONTRACT_TEXT = "Stromliefervertrag Nr. 4711\n\fMindestlaufzeit 24 Monate.\nKündigungsfrist "
            + "3 Monate zum Ende der Laufzeit.";

    private final InMemoryConversations conversations = new InMemoryConversations();
    private final ChatHandler handler = new ChatHandler(conversations, new Texts(), new Contracts(), new Tasks(),
            BERLIN, Clock.fixed(NOW, ZoneOffset.UTC));

    private Conversation conversation(String question) {
        Conversation conversation = Conversation.start(UUID.randomUUID(), LISA, UUID.randomUUID(), question,
                ProductLocale.DE, RunId.random(), NOW.minusSeconds(60));
        conversations.save(conversation);
        return conversation;
    }

    private static RunContext run(Conversation conversation, UUID turn, RunId id, MemberId member) {
        return new RunContext(id, Workload.CHAT, 1, new Actor(member), new ModelSelection("anthropic", "m"),
                List.of(), Map.of(ChatHandler.CONVERSATION, conversation.id().toString(), ChatHandler.TURN,
                        turn.toString()), null);
    }

    private static RunContext run(Conversation conversation) {
        return run(conversation, conversation.latest().id(), conversation.latest().run(), LISA);
    }

    private static ModelTurn answer(Map<String, Object> output) {
        return new ModelTurn(List.of(new ContentPart.Text("{}")), ModelTurn.Stop.END, TokenUsage.NONE, "m", "{}",
                output, null);
    }

    private static Map<String, Object> output(List<Map<String, Object>> citations) {
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("answer", "Kündigen bis 31.10.2026 [1].");
        output.put("evidence", "COMPLETE");
        output.put("citations", citations);
        output.put("caveats", List.of("Der Arbeitspreis steht nicht in den Unterlagen.", " "));
        return output;
    }

    private static Map<String, Object> cite(String kind, Object id, String quote) {
        return Map.of("kind", kind, "id", String.valueOf(id), "quote", quote);
    }

    @Test
    void theModelGetsTheHouseholdDateTheFallbackLanguageAndTheGrantedReadOnlyTools() {
        Conversation conversation = conversation("Wann endet der Stromvertrag?");

        PreparedRun prepared = handler.prepare(run(conversation));

        assertThat(prepared.system()).contains("Today is 2026-10-03 (Saturday) in the household's time zone "
                + "Europe/Berlin").contains("if that is unclear, in German");
        assertThat(prepared.messages()).singleElement().satisfies(message ->
                assertThat(message.parts()).containsExactly(new ContentPart.Text("Wann endet der Stromvertrag?")));
        assertThat(handler.grantedTools()).containsExactlyInAnyOrder("household_catalog", "search_documents",
                "read_document", "list_contracts", "read_contract", "list_tasks");
    }

    @Test
    void earlierAnsweredQuestionsComeFirstWithTheirSources() {
        Conversation conversation = conversation("Wann endet der Stromvertrag?");
        ChatAnswer earlier = new ChatAnswer("Am 31.01.2027 [1].", Evidence.COMPLETE, List.of(new Citation(1,
                SourceKind.DOCUMENT, CONTRACT_DOC, "Stromvertrag", "Mindestlaufzeit 24 Monate", 2, Check.VERIFIED)),
                List.of());
        conversation.answer(conversation.latest().id(), conversation.latest().run(), earlier, NOW);
        UUID failed = UUID.randomUUID();
        conversation.ask(failed, "Und der Gasvertrag?", ProductLocale.DE, RunId.random(), NOW, true);
        UUID current = UUID.randomUUID();
        RunId currentRun = RunId.random();
        conversation.ask(current, "Und wie hoch ist der Grundpreis?", ProductLocale.DE, currentRun, NOW, true);
        conversations.save(conversation);

        PreparedRun prepared = handler.prepare(run(conversation, current, currentRun, LISA));

        assertThat(prepared.messages()).extracting(ModelMessage::role).containsExactly(ModelMessage.Role.USER,
                ModelMessage.Role.ASSISTANT, ModelMessage.Role.USER);
        assertThat(((ContentPart.Text) prepared.messages().get(1).parts().getFirst()).text())
                .startsWith("Am 31.01.2027 [1].").contains("[1] DOCUMENT " + CONTRACT_DOC + " \"Stromvertrag\"");
        assertThat(((ContentPart.Text) prepared.messages().get(2).parts().getFirst()).text())
                .isEqualTo("Und wie hoch ist der Grundpreis?");
    }

    @Test
    void anotherMembersConversationIsNeverRead() {
        Conversation conversation = conversation("Wann endet der Stromvertrag?");

        assertThatThrownBy(() -> handler.prepare(run(conversation, conversation.latest().id(),
                conversation.latest().run(), MemberId.random()))).isInstanceOf(NotAuthorizedException.class);
    }

    @Test
    void everyCitationIsCheckedAgainstItsSource() {
        Conversation conversation = conversation("Wann kann ich kündigen?");

        ValidatedOutput output = handler.validate(run(conversation), answer(output(List.of(
                cite("DOCUMENT", CONTRACT_DOC, "Kündigungsfrist 3 Monate zum Ende der Laufzeit"),
                cite("DOCUMENT", CONTRACT_DOC, "Kündigungsfrist 6 Monate"),
                cite("DOCUMENT", TRASHED_DOC, "Mindestlaufzeit"),
                cite("DOCUMENT", UUID.randomUUID(), "anything"),
                cite("CONTRACT", CONTRACT, ""),
                cite("TASK", OPEN_TASK, "Stromvertrag kündigen"),
                cite("TASK", TRASHED_TASK, "")))));

        ChatAnswer answer = (ChatAnswer) output.value();
        assertThat(answer.citations()).extracting(Citation::number, Citation::check, Citation::title, Citation::page)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(1, Check.VERIFIED, "Stromvertrag", 2),
                        org.assertj.core.groups.Tuple.tuple(2, Check.NOT_IN_SOURCE, "Stromvertrag", null),
                        org.assertj.core.groups.Tuple.tuple(3, Check.SOURCE_UNAVAILABLE, null, null),
                        org.assertj.core.groups.Tuple.tuple(4, Check.SOURCE_UNAVAILABLE, null, null),
                        org.assertj.core.groups.Tuple.tuple(5, Check.VERIFIED, "Strom Muster", null),
                        org.assertj.core.groups.Tuple.tuple(6, Check.VERIFIED, "Stromvertrag kündigen", null),
                        org.assertj.core.groups.Tuple.tuple(7, Check.SOURCE_UNAVAILABLE, null, null));
        assertThat(answer.citations().get(4).quote()).as("empty quote").isNull();
        assertThat(answer.caveats()).containsExactly("Der Arbeitspreis steht nicht in den Unterlagen.");
        assertThat(output.json()).as("the run keeps no content").doesNotContain("Kündigen", "Arbeitspreis")
                .contains("\"citations\":7").contains("\"verifiedCitations\":3");
    }

    @Test
    void answersOutsideTheContractAreInvalidOutput() {
        Conversation conversation = conversation("Frage");
        Map<String, Object> noText = output(List.of());
        noText.put("answer", " ");
        Map<String, Object> unknownEvidence = output(List.of());
        unknownEvidence.put("evidence", "MAYBE");

        assertThatThrownBy(() -> handler.validate(run(conversation), answer(noText)))
                .isInstanceOf(InvalidModelOutputException.class);
        assertThatThrownBy(() -> handler.validate(run(conversation), answer(unknownEvidence)))
                .isInstanceOf(InvalidModelOutputException.class);
        assertThatThrownBy(() -> handler.validate(run(conversation),
                answer(output(List.of(cite("DOCUMENT", "not-an-id", "x"))))))
                .isInstanceOfSatisfying(InvalidModelOutputException.class,
                        invalid -> assertThat(invalid.code()).isEqualTo("output.unknownSource"));
        assertThatThrownBy(() -> handler.validate(run(conversation), answer(null)))
                .isInstanceOf(InvalidModelOutputException.class);
    }

    @Test
    void theAnswerIsStoredOnceInTheConversationOfTheTurn() {
        Conversation conversation = conversation("Wann kann ich kündigen?");
        RunContext run = run(conversation);
        ValidatedOutput output = handler.validate(run, answer(output(List.of())));

        assertThat(handler.effectKey(run, output)).contains("chat:" + conversation.latest().id());
        handler.apply(run, output);
        handler.apply(run, output);

        Conversation stored = conversations.findById(conversation.id()).orElseThrow();
        assertThat(stored.latest().answer()).map(ChatAnswer::text).contains("Kündigen bis 31.10.2026 [1].");
        assertThat(conversations.saves).as("created plus one answer").isEqualTo(2);
    }

    @Test
    void aDeletedConversationOrASupersededRunStoresNothing() {
        Conversation conversation = conversation("Wann kann ich kündigen?");
        ValidatedOutput output = handler.validate(run(conversation), answer(output(List.of())));

        handler.apply(run(conversation, conversation.latest().id(), RunId.random(), LISA), output);
        assertThat(conversations.findById(conversation.id()).orElseThrow().latest().answer()).isEmpty();

        conversations.delete(conversation.id());
        handler.apply(run(conversation), output);
        assertThat(conversations.findById(conversation.id())).isEmpty();
    }

    final class Texts implements DocumentTextQuery {
        @Override
        public DocumentText text(Actor actor, DocumentId id) {
            if (id.value().equals(CONTRACT_DOC)) {
                return new DocumentText(id, "Stromvertrag", CONTRACT_TEXT);
            }
            if (id.value().equals(TRASHED_DOC)) {
                throw new TrashedException("document", id.toString());
            }
            throw new NotFoundException("document", id.toString());
        }
    }

    final class Contracts implements ContractQueries {
        @Override
        public List<ContractSummary> list(Actor actor) {
            return List.of();
        }

        @Override
        public ContractView get(Actor actor, ContractId id) {
            if (!id.value().equals(CONTRACT)) {
                throw new NotFoundException("contract", id.toString());
            }
            return new ContractView(id, "Strom Muster", "Stadtwerke Muster", "ACTIVE", LISA, NOW, NOW, 1, null, null,
                    List.of(), List.of(), List.of(), null);
        }

        @Override
        public List<ContractSummary> forDocument(Actor actor, DocumentId documentId) {
            return List.of();
        }

        @Override
        public List<ContractSummary> trashed(Actor actor) {
            return List.of();
        }
    }

    final class Tasks implements TaskQueries {
        @Override
        public TaskSummary get(Actor actor, TaskId id) {
            if (id.value().equals(OPEN_TASK)) {
                return task(id, null);
            }
            if (id.value().equals(TRASHED_TASK)) {
                return task(id, new Trash(LISA, NOW, NOW.plusSeconds(3600)));
            }
            throw new NotFoundException("task", id.toString());
        }

        private TaskSummary task(TaskId id, Trash trash) {
            return new TaskSummary(id, "Stromvertrag kündigen", null, LISA, "OPEN", null, null, null, null, LISA,
                    NOW, NOW, null, null, 1, trash);
        }

        @Override
        public List<TaskSummary> list(Actor actor, boolean includeDone) {
            return List.of();
        }

        @Override
        public List<TaskSummary> forDocument(Actor actor, DocumentId documentId) {
            return List.of();
        }

        @Override
        public List<TaskSummary> trashed(Actor actor) {
            return List.of();
        }

        @Override
        public TodayView today(Actor actor) {
            throw new UnsupportedOperationException();
        }
    }
}
