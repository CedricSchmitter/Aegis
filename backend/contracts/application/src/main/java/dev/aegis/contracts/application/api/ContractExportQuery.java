package dev.aegis.contracts.application.api;

import dev.aegis.shared.Actor;
import java.util.List;

/** Public contract for portability: every contract with every observation and its provenance. */
public interface ContractExportQuery {
    List<ContractExportRecord> exportAll(Actor actor);
}
