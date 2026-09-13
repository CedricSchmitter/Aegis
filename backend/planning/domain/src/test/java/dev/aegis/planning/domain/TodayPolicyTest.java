package dev.aegis.planning.domain;

import static org.assertj.core.api.Assertions.assertThat;

import dev.aegis.shared.MemberId;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

class TodayPolicyTest {
    private static final Instant NOW = Instant.parse("2026-09-13T10:00:00Z");
    private static final LocalDate TODAY = LocalDate.of(2026, 9, 13);
    private final MemberId anna = MemberId.random();

    private Task task(String title, MemberId owner, LocalDate due, LocalDate planned) {
        return Task.create(title, "", owner, due, planned, null, anna, NOW);
    }

    @Test
    void eachOpenTaskAppearsExactlyOnceInItsMostUrgentBucket() {
        Task overdue = task("Overdue unassigned", null, TODAY.minusDays(1), TODAY);
        Task dueToday = task("Due today", anna, TODAY, TODAY);
        Task planned = task("Planned today", anna, TODAY.plusDays(30), TODAY);
        Task upcoming = task("Upcoming", null, TODAY.plusDays(3), null);
        Task unassigned = task("Someday unassigned", null, null, null);
        Task assignedLater = task("Assigned later", anna, TODAY.plusDays(60), null);
        Task done = task("Done", anna, TODAY, TODAY);
        done.complete(anna, NOW);

        var buckets = TodayPolicy.build(List.of(done, assignedLater, unassigned, upcoming, planned, dueToday, overdue),
                TODAY);

        assertThat(buckets.overdue()).containsExactly(overdue);
        assertThat(buckets.dueToday()).containsExactly(dueToday);
        assertThat(buckets.plannedToday()).containsExactly(planned);
        assertThat(buckets.upcoming()).containsExactly(upcoming);
        assertThat(buckets.unassigned()).containsExactly(unassigned);
    }

    @Test
    void bucketsAreSortedByDueDateThenTitleForDeterminism() {
        Task b = task("b", anna, TODAY.minusDays(1), null);
        Task a = task("a", anna, TODAY.minusDays(1), null);
        Task older = task("z", anna, TODAY.minusDays(5), null);

        var first = TodayPolicy.build(List.of(b, a, older), TODAY);
        var second = TodayPolicy.build(List.of(a, older, b), TODAY);

        assertThat(first.overdue()).containsExactly(older, a, b);
        assertThat(second.overdue()).containsExactly(older, a, b);
    }
}
