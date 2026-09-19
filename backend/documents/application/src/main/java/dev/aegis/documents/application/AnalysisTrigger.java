package dev.aegis.documents.application;

import dev.aegis.documents.domain.Document;

/** Starts the automatic analysis of an upload once archival made its OCR text available. */
public interface AnalysisTrigger {
    AnalysisTrigger NONE = document -> {
    };

    /** Runs inside the archival transaction, so the archived state and the analysis request commit together. */
    void archived(Document document);
}
