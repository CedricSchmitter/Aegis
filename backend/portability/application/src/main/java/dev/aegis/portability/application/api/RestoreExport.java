package dev.aegis.portability.application.api;

import dev.aegis.shared.Actor;
import java.util.List;
import java.util.Map;

/**
 * Imports an export generation into this instance. Members are matched by username; documents keep their stable
 * Aegis ids and are re-archived; documents created by the page editor get their page ranges back; tasks and
 * contracts keep ids, fact history and source links. Running twice is safe.
 */
public interface RestoreExport {

    record RestoreReport(String generation, int documentsImported, int documentsAlreadyPresent, int tasksImported,
            int tasksAlreadyPresent, int labelsCreated, int foldersCreated, int classificationsApplied,
            int classificationsKept, int analysesApplied, int contractsImported, int contractsAlreadyPresent,
            int derivationsImported, int derivationsAlreadyPresent, Map<String, String> memberIdMapping, List<String> warnings) {
    }

    RestoreReport restore(Actor actor, String sourceLocation);
}
