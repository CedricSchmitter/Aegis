package dev.aegis.documents.domain.analysis;

/** Namespace for the small value types of the automatic document analysis. */
public final class AnalysisTypes {
    private AnalysisTypes() {
    }

    /** Where the automatic analysis of a document stands, apart from archival and from the AI run itself. */
    public enum Status {
        /** Never requested; existing and restored documents start here and are only analyzed on request. */
        NOT_REQUESTED,
        /** Uploaded with automatic classification on; the run is requested once the archive holds the OCR text. */
        AWAITING_ARCHIVE,
        /** A run for the current generation was requested; its progress and failures are the run's state. */
        REQUESTED,
        /** The run could not be requested (AI not configured, workload unavailable); retry later. */
        UNAVAILABLE,
        /** The result of the current generation was applied. */
        COMPLETED
    }

    /** Who started the current generation. */
    public enum Trigger {
        UPLOAD,
        MANUAL,
        /** A bulk run over existing documents that a member started explicitly (issue #115). */
        BULK
    }

    /** Review state of a generated title, of a new label name or of a subject suggestion. */
    public enum Decision {
        /** Applied automatically under the policy; the member may still revert it. */
        APPLIED,
        /** Waiting for a member's decision. */
        SUGGESTED,
        ACCEPTED,
        /** Rejected by a member; later runs do not propose the same value again. */
        REJECTED;

        public boolean pending() {
            return this == SUGGESTED;
        }
    }

    /** Status of the document (issue) date; only {@link #FOUND} dates appear in filenames. */
    public enum DateStatus {
        /** The date and a verbatim quote containing it were found in the text. */
        FOUND,
        /** The classifier reported several candidate issue dates. */
        CONFLICTING,
        /** A date was reported but its quote is not in the text or does not contain it. */
        UNVERIFIED,
        UNKNOWN
    }

    /**
     * Conditions reported by the classifier that make the whole result doubtful; any of them turns every value into a
     * suggestion.
     */
    public enum Flag {
        POOR_OCR,
        NO_TEXT,
        CONFLICTING_INFORMATION,
        EMBEDDED_INSTRUCTIONS,
        MULTIPLE_DOCUMENTS
    }
}
