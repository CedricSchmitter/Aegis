package dev.aegis.assistant.adapters.tools;

import static org.assertj.core.api.Assertions.assertThat;

import dev.aegis.assistant.application.chat.PassageSearch;
import dev.aegis.assistant.application.ports.AiTool;
import dev.aegis.assistant.domain.RunId;
import dev.aegis.documents.application.api.ContentUnavailableException;
import dev.aegis.documents.application.api.DocumentTextQuery.DocumentText;
import dev.aegis.shared.Actor;
import dev.aegis.shared.DocumentId;
import dev.aegis.shared.MemberId;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class FindInDocumentToolTest {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Actor ACTOR = new Actor(MemberId.random());
    private static final AiTool.Invocation INVOCATION = new AiTool.Invocation(RunId.random(), ACTOR);

    private static JsonNode invoke(DocumentText text, Map<String, Object> input) {
        AiTool.Result result = new FindInDocumentTool((actor, id) -> {
            assertThat(actor).isEqualTo(ACTOR);
            assertThat(id).isEqualTo(text.id());
            return text;
        }).invoke(INVOCATION, input);
        assertThat(result.error()).isFalse();
        return JSON.readTree(result.content());
    }

    @Test
    void aPassageCarriesItsPageTheOffsetToReadOnAndFramedTextWithPageMarkers() {
        DocumentText text = new DocumentText(DocumentId.random(), "Vertrag",
                List.of("Seite eins", "§ 9 Kündigung mit einer Frist von drei Monaten"), true);

        JsonNode result = invoke(text, Map.of("id", text.id().value().toString(), "query", "Kündigung Widerruf"));

        assertThat(result.path("pages").asInt()).isEqualTo(2);
        assertThat(result.path("wordsNotFound").path(0).asString()).isEqualTo("Widerruf");
        JsonNode passage = result.path("passages").path(0);
        assertThat(passage.path("page").asInt()).isEqualTo(2);
        assertThat(passage.path("offset").asInt()).isZero();
        assertThat(passage.path("words").path(0).asString()).isEqualTo("Kündigung");
        assertThat(passage.path("text").asString()).startsWith("<untrusted_content source=\"document:")
                .contains("--- page 2 ---\n§ 9 Kündigung");
        assertThat(result.path("omittedPassages").asInt()).isZero();
        assertThat(result.has("note")).isFalse();
    }

    @Test
    void passagesAreOmittedWholeAndCountedToKeepTheResultBelowTheToolLimit() {
        String block = "Kündigung \"" + "ü\\\n".repeat(PassageSearch.CONTEXT) + "\" ";
        DocumentText text = DocumentText.unpaged(DocumentId.random(), "Lang", block.repeat(40));

        String bounded = FindInDocumentTool.response(text.id().value(), text,
                PassageSearch.find(text, PassageSearch.terms("Kündigung"), 40));

        assertThat(bounded.length()).isLessThanOrEqualTo(FindInDocumentTool.MAX_JSON);
        JsonNode result = JSON.readTree(bounded);
        assertThat(result.path("passages").size()).isBetween(1, 39);
        assertThat(result.path("passages").size() + result.path("omittedPassages").asInt()).isEqualTo(40);
    }

    @Test
    void aDocumentWithoutTextOrWithoutMatchesSaysSoWithoutClaimingAbsence() {
        DocumentText empty = DocumentText.unpaged(DocumentId.random(), "Foto", "");
        assertThat(invoke(empty, Map.of("id", empty.id().value().toString(), "query", "Kündigung"))
                .path("textRecognized").asBoolean(true)).isFalse();

        DocumentText other = DocumentText.unpaged(DocumentId.random(), "Brief", "Nur ein Gruß.");
        JsonNode none = invoke(other, Map.of("id", other.id().value().toString(), "query", "Kündigung"));
        assertThat(none.path("passages").size()).isZero();
        assertThat(none.path("note").asString()).contains("does not prove");
    }

    @Test
    void invalidInputAndAnUnavailableArchiveAreReportedToTheModel() {
        FindInDocumentTool tool = new FindInDocumentTool((actor, id) -> {
            throw new ContentUnavailableException("archive down", null);
        });
        String id = UUID.randomUUID().toString();

        for (Map<String, Object> input : List.of(Map.<String, Object>of("id", id), Map.<String, Object>of("id", id,
                "query", "a -"), Map.<String, Object>of("id", id, "query", "eins zwei drei vier fünf sechs sieben "
                + "acht neun"), Map.<String, Object>of("query", "Kündigung"))) {
            AiTool.Result result = tool.invoke(INVOCATION, input);
            assertThat(result.error()).as(input.toString()).isTrue();
            assertThat(result.content()).contains("input.invalid");
        }
        AiTool.Result unavailable = tool.invoke(INVOCATION, Map.of("id", id, "query", "Kündigung"));
        assertThat(unavailable.error()).isTrue();
        assertThat(unavailable.content()).contains("document.textUnavailable");
    }
}
