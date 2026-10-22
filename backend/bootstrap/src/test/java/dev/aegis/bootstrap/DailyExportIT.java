package dev.aegis.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.aegis.bootstrap.support.PostgresIT;
import dev.aegis.identity.application.api.MemberCredentialsQuery;
import dev.aegis.planning.application.api.TaskCommands;
import dev.aegis.planning.application.api.TaskCommands.CreateTaskCommand;
import dev.aegis.portability.application.api.ExportQueries;
import dev.aegis.portability.application.api.ExportStatus;
import dev.aegis.portability.application.api.ExportSummary;
import dev.aegis.portability.application.api.RefreshExport;
import dev.aegis.portability.application.api.RefreshExport.Outcome;
import dev.aegis.shared.Actor;
import dev.aegis.shared.NotAuthorizedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * The daily export (ADR-0029) against PostgreSQL: the exporter household process reads every context through its
 * export query, a second run over unchanged data writes nothing, and a change writes a new complete generation.
 */
class DailyExportIT extends PostgresIT {
    @Autowired
    RefreshExport refresh;
    @Autowired
    ExportQueries exports;
    @Autowired
    TaskCommands tasks;
    @Autowired
    MemberCredentialsQuery members;

    @Test
    void unchangedDataWritesNoNewGenerationAndAChangeWritesOne() {
        Actor anna = new Actor(members.findCredentials(ANNA).orElseThrow().id());
        // Other test classes share the database, so this test makes its own change before the first run.
        tasks.create(anna, new CreateTaskCommand("Täglicher Export " + System.nanoTime(), "", anna.memberId(),
                LocalDate.of(2027, 1, 31), null, null));

        RefreshExport.Result first = refresh.refresh(RefreshExport.EXPORTER);
        RefreshExport.Result unchanged = refresh.refresh(RefreshExport.EXPORTER);

        assertThat(first.outcome()).isEqualTo(Outcome.CREATED);
        assertThat(first.current().trigger()).isEqualTo(ExportSummary.SCHEDULED);
        assertThat(Files.isRegularFile(Path.of(first.current().location()).resolve("manifest.json"))).isTrue();
        assertThat(unchanged.outcome()).isEqualTo(Outcome.UNCHANGED);
        assertThat(unchanged.current().generation()).isEqualTo(first.current().generation());

        tasks.create(anna, new CreateTaskCommand("Noch eine Aufgabe " + System.nanoTime(), "", anna.memberId(),
                null, null, null));
        RefreshExport.Result changed = refresh.refresh(RefreshExport.EXPORTER);

        assertThat(changed.outcome()).isEqualTo(Outcome.CREATED);
        assertThat(changed.current().taskCount()).isEqualTo(first.current().taskCount() + 1);
        ExportStatus status = exports.status(anna);
        assertThat(status.latest().generation()).isEqualTo(changed.current().generation());
        assertThat(status.lastCheckedAt()).isNotNull();
    }

    @Test
    void neitherAMemberNorAnotherProcessRunsTheDailyExport() {
        Actor anna = new Actor(members.findCredentials(ANNA).orElseThrow().id());

        assertThatThrownBy(() -> refresh.refresh(anna)).isInstanceOf(NotAuthorizedException.class);
        assertThatThrownBy(() -> refresh.refresh(Actor.householdProcess("review-reconciler")))
                .isInstanceOf(NotAuthorizedException.class);
    }
}
