package dev.aegis.planning.domain;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Deterministic daily view. Every open task appears at most once, in its most urgent bucket: overdue, due today,
 * planned today, upcoming (due within the horizon) or unassigned. Nothing is created or mutated.
 */
public final class TodayPolicy {
    public static final int UPCOMING_HORIZON_DAYS = 7;

    public record TodayBuckets(LocalDate date, List<Task> overdue, List<Task> dueToday, List<Task> plannedToday,
            List<Task> upcoming, List<Task> unassigned) {
    }

    private TodayPolicy() {
    }

    public static TodayBuckets build(List<Task> tasks, LocalDate today) {
        List<Task> overdue = new ArrayList<>();
        List<Task> dueToday = new ArrayList<>();
        List<Task> plannedToday = new ArrayList<>();
        List<Task> upcoming = new ArrayList<>();
        List<Task> unassigned = new ArrayList<>();
        LocalDate horizon = today.plusDays(UPCOMING_HORIZON_DAYS);
        for (Task task : tasks) {
            if (!task.isOpen()) {
                continue;
            }
            if (task.isOverdueOn(today)) {
                overdue.add(task);
            } else if (task.isDueOn(today)) {
                dueToday.add(task);
            } else if (task.isPlannedOn(today)) {
                plannedToday.add(task);
            } else if (task.dueDate().map(due -> !due.isAfter(horizon)).orElse(false)) {
                upcoming.add(task);
            } else if (task.isUnassigned()) {
                unassigned.add(task);
            }
        }
        Comparator<Task> byDueThenTitle = Comparator
                .comparing((Task t) -> t.dueDate().orElse(LocalDate.MAX))
                .thenComparing(t -> t.plannedDate().orElse(LocalDate.MAX))
                .thenComparing(Task::title, String.CASE_INSENSITIVE_ORDER);
        overdue.sort(byDueThenTitle);
        dueToday.sort(byDueThenTitle);
        plannedToday.sort(byDueThenTitle);
        upcoming.sort(byDueThenTitle);
        unassigned.sort(byDueThenTitle);
        return new TodayBuckets(today, List.copyOf(overdue), List.copyOf(dueToday), List.copyOf(plannedToday),
                List.copyOf(upcoming), List.copyOf(unassigned));
    }
}
