package dev.aegis.assistant.adapters.evaluation;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * The assistant's reference questions with their synthetic documents ({@code fixtures/assistant-eval}), shared with
 * the scripted check against the running application ({@code ops/eval/chat_reference_check.py}).
 */
record ReferenceSet(String description, int version, LocalDate referenceDate, String folderRoot,
        List<Document> documents, List<Question> questions) {
    static final Path FILE = Path.of("..", "..", "..", "fixtures", "assistant-eval", "reference-set.json");

    /** A label by built-in key or, for organizations, by name. */
    record Label(String facet, String builtin, String name) {
    }

    record Document(String key, String title, LocalDate documentDate, List<String> folder, List<Label> labels,
            String labeling, List<List<String>> pages) {
    }

    record Question(String id, String locale, String question, List<String> purpose, List<String> expectedSources,
            List<String> acceptableSources, List<String> distractors, List<List<String>> expectedFacts,
            List<String> expectedEvidence) {
    }

    static ReferenceSet load() throws IOException {
        return JsonMapper.builder().disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).build()
                .readValue(Files.readString(FILE), ReferenceSet.class);
    }
}
