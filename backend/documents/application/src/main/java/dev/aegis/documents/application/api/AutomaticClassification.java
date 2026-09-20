package dev.aegis.documents.application.api;

import dev.aegis.shared.Actor;
import dev.aegis.shared.DocumentId;
import dev.aegis.shared.MemberId;
import java.util.List;
import java.util.UUID;

/**
 * Contract for the automatic classifier (issue #23, ADR-0004). The classifier reads the input, lets the model
 * propose, has the proposal assessed against the text and the vocabulary, and applies the assessment once. The
 * documents context decides what becomes effective; the classifier never writes labels, titles or names itself.
 *
 * <p>{@link #input} and {@link #assess} call the archive and must run outside database transactions;
 * {@link #current} and {@link #apply} run inside the AI run's completion transaction.
 */
public interface AutomaticClassification {

    /** A vocabulary entry the model may choose; built-in entries carry a key instead of a custom name. */
    record VocabularyEntry(UUID id, String facet, String builtinKey, String name, List<String> aliases) {
    }

    /**
     * A folder of the tree as trusted catalog context (ADR-0005): its full path, the labels and subjects it passes
     * on and how many documents are filed directly in it. Implied labels are named the way the vocabulary lists
     * them, so both blocks use the same tokens.
     */
    record FolderEntry(UUID id, List<String> path, List<String> impliedLabels, List<String> impliedSubjects,
            long documentCount) {
        public FolderEntry {
            path = List.copyOf(path);
            impliedLabels = List.copyOf(impliedLabels);
            impliedSubjects = List.copyOf(impliedSubjects);
        }
    }

    /** The folder catalog handed to the classifier; {@code omitted} counts folders left out by the size bound. */
    record FolderCatalog(List<FolderEntry> folders, int omitted) {
        public static final FolderCatalog EMPTY = new FolderCatalog(List.of(), 0);

        public FolderCatalog {
            folders = List.copyOf(folders);
        }
    }

    /**
     * What the classifier analyzes. {@code text} is the archive's OCR text (untrusted; may be empty or cut to
     * {@code maxTextChars}); {@code labels} are the document's own effective labels and pending suggestions.
     */
    record Input(DocumentId id, int generation, long classificationVersion, String title, String titleOrigin,
            String originalFilename, String text, boolean textTruncated, List<VocabularyEntry> vocabulary,
            FolderCatalog folders, List<String> currentFolderPath, List<LabelChip> labels,
            List<SubjectView> subjects) {
    }

    /** A label named by the model: an existing entry by id, or a name (resolved through names and aliases). */
    record LabelClaim(UUID labelId, String name, Double confidence, String evidence) {
    }

    record SubjectClaim(String kind, MemberId memberId, Double confidence, String evidence) {
    }

    /**
     * Where the model would file the document: an existing folder by id, or a path that may still be missing,
     * anchored at an existing folder where possible. Exactly one of {@code folderId} and {@code newPath} is used.
     */
    record PlacementClaim(UUID folderId, UUID anchorFolderId, List<String> newPath, Double confidence,
            String evidence) {
        public PlacementClaim {
            newPath = newPath == null ? List.of() : List.copyOf(newPath);
        }
    }

    /**
     * The model's structured answer, already schema-checked by the classifier. {@code analyzedTitle} is informational;
     * the assessment records the title the document has when it is assessed.
     */
    record Proposal(int generation, long classificationVersion, String analyzedTitle, String title,
            String documentDate, String documentDateEvidence, boolean conflictingDates, LabelClaim documentType,
            LabelClaim correspondent, List<LabelClaim> topics, String shortTopic, List<SubjectClaim> subjects,
            PlacementClaim placement, List<String> flags) {
        public Proposal {
            topics = topics == null ? List.of() : List.copyOf(topics);
            subjects = subjects == null ? List.of() : List.copyOf(subjects);
            flags = flags == null ? List.of() : List.copyOf(flags);
        }
    }

    /** A resolved vocabulary label with the policy decision whether it becomes effective. */
    record LabelDecision(UUID labelId, String facet, Double confidence, boolean evidenced, boolean apply) {
    }

    record CandidateDecision(String facet, String name, String evidence, Double confidence) {
    }

    record DateDecision(String value, String evidence, String status) {
    }

    /**
     * A placement that survived the text and shape checks. Whether it is applied or recorded as a suggestion is
     * decided when it is applied, because only then is the current folder tree known.
     */
    record PlacementDecision(UUID folderId, UUID anchorFolderId, List<String> newPath, Double confidence,
            String evidence, boolean evidenced) {
        public PlacementDecision {
            newPath = newPath == null ? List.of() : List.copyOf(newPath);
        }
    }

    /** The policy's verdict on a proposal; applied as a whole by {@link #apply}. */
    record Assessment(DocumentId id, int generation, long classificationVersion, String analyzedTitle, String title,
            List<LabelDecision> labels, UUID correspondentLabelId, DateDecision documentDate, String shortTopic,
            List<String> flags, List<CandidateDecision> candidates, List<SubjectClaim> subjects,
            PlacementDecision placement) {
        public Assessment {
            labels = List.copyOf(labels);
            flags = List.copyOf(flags);
            candidates = List.copyOf(candidates);
            subjects = List.copyOf(subjects);
        }
    }

    record Applied(int appliedLabels, int suggestedLabels, boolean titleApplied, boolean documentMoved,
            int pendingSuggestions) {
    }

    Input input(Actor actor, DocumentId id, int generation);

    /** Whether the analyzed classification version and analysis generation are still current. */
    boolean current(Actor actor, DocumentId id, long classificationVersion, int generation);

    /** Checks the proposal against the document text and vocabulary under the classification policy. */
    Assessment assess(Actor actor, DocumentId id, Proposal proposal);

    /**
     * Applies an assessment once. Refuses a stale generation or classification version; never overrides a member
     * decision.
     *
     * @throws dev.aegis.shared.StaleVersionException when the document changed after the analysis
     */
    Applied apply(Actor actor, Assessment assessment, String runRef, String model);
}
