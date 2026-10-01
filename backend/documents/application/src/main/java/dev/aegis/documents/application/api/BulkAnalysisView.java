package dev.aegis.documents.application.api;

import dev.aegis.shared.DocumentId;
import dev.aegis.shared.MemberId;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * A bulk classification of existing documents (issue #115) with its progress and grouped results. {@code state} is
 * {@code RUNNING}, {@code COMPLETED}, {@code CANCELLED} or {@code STOPPED} (with {@code stopCode}). Counts:
 * {@code pending} documents wait for their turn, {@code inFlight} are being classified; of the finished ones,
 * {@code classified} got a result, {@code needsReview} of those still wait for a member, {@code failed} still show a
 * failed run, {@code notClassified} were refused because the requester may not use AI, {@code superseded} were
 * requested again by a member. {@code waitingForBudgetUntil} is set while the run waits for the next budget day.
 * Groups are bounded: identical folder proposals, failures and skips by reason, and up to {@link #MAX_LISTED}
 * documents that need a review.
 */
public record BulkAnalysisView(UUID id, String state, String stopCode, Scope scope, MemberId requestedBy,
        Instant requestedAt, MemberId cancelledBy, Instant cancelledAt, Instant finishedAt, int total, int pending,
        int inFlight, int classified, int needsReview, int failed, int notClassified, int skipped, int cancelled,
        int superseded, Instant waitingForBudgetUntil, List<PlacementGroup> placementGroups,
        List<ReasonGroup> failures, List<ReasonGroup> skips, List<DocumentRef> reviewDocuments) {
    public static final int MAX_LISTED = 50;
    public static final int MAX_EXAMPLES = 5;

    public BulkAnalysisView {
        placementGroups = List.copyOf(placementGroups);
        failures = List.copyOf(failures);
        skips = List.copyOf(skips);
        reviewDocuments = List.copyOf(reviewDocuments);
    }

    /**
     * {@code kind} is {@code ALL}, {@code UNSORTED} or {@code FOLDER}; {@code folderPath} names the folder as it is
     * now and is empty once the folder was deleted.
     */
    public record Scope(String kind, UUID folderId, boolean includeSubfolders, List<String> folderPath) {
        public Scope {
            folderPath = folderPath == null ? List.of() : List.copyOf(folderPath);
        }
    }

    public record DocumentRef(DocumentId id, String title) {
    }

    /**
     * Documents of the run with the same pending folder proposal (ADR-0005). Accepting it on {@code example} with
     * "also for the other documents" files all of them.
     */
    public record PlacementGroup(String key, List<String> path, List<String> newFolders, int documents,
            DocumentRef example) {
        public PlacementGroup {
            path = List.copyOf(path);
            newFolders = List.copyOf(newFolders);
        }
    }

    /** Documents that failed or were skipped for the same stable reason, with a few examples. */
    public record ReasonGroup(String code, int documents, List<DocumentRef> examples) {
        public ReasonGroup {
            examples = List.copyOf(examples);
        }
    }
}
