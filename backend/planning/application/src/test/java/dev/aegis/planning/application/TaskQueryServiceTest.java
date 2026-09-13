package dev.aegis.planning.application;

import static org.assertj.core.api.Assertions.assertThat;

import dev.aegis.planning.application.api.TaskQueries.TodayView;
import dev.aegis.planning.domain.Task;
import dev.aegis.shared.Actor;
import dev.aegis.shared.MemberId;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import org.junit.jupiter.api.Test;

class TaskQueryServiceTest {
    private final MemberId anna = MemberId.random();

    @Test
    void todayUsesTheInjectedClockInTheHouseholdZoneAndReportsCalendarNotConnected() {
        // 23:30 UTC on 12 September is already 13 September in Europe/Berlin.
        Clock clock = Clock.fixed(Instant.parse("2026-09-12T23:30:00Z"), ZoneId.of("Europe/Berlin"));
        var repository = new PlanningTestSupport.InMemoryTaskRepository();
        Task due = Task.create("Due on the 13th", "", anna, LocalDate.of(2026, 9, 13), null, null, anna,
                clock.instant());
        repository.save(due);
        var service = new TaskQueryService(repository, new PlanningTestSupport.NoCalendar(),
                new TaskPresenter(new PlanningTestSupport.KnownDocuments()), new PlanningTestSupport.Members(anna),
                clock);

        TodayView today = service.today(new Actor(anna));

        assertThat(today.date()).isEqualTo(LocalDate.of(2026, 9, 13));
        assertThat(today.dueToday()).extracting("title").containsExactly("Due on the 13th");
        assertThat(today.overdue()).isEmpty();
        assertThat(today.calendar().connected()).isFalse();
        assertThat(today.calendar().writeSupported()).isFalse();
    }
}
