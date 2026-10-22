package dev.aegis.contracts.application.api;

import dev.aegis.shared.Actor;
import java.util.List;

/**
 * Public contract for portability: every contract with every observation and its provenance. Open to household
 * processes (ADR-0029), so the daily export can read it.
 */
public interface ContractExportQuery {
    List<ContractExportRecord> exportAll(Actor actor);
}
