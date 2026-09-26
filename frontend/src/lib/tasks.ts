import type { ContractDeadlineItemDto, TaskDto, TodayResponse } from "../api/types";

/**
 * Presentation rules of Today, the task list and the contract countdowns (issue #27). Dates are ISO local dates;
 * the contractual due date and the planned work day stay separate values, and nothing here moves either of them.
 */

/** Cancellation deadlines within this many days are shown on Today and highlighted in the contract list. */
export const DEADLINE_WINDOW_DAYS = 30;

/** Whole days from `from` to `date`; negative when the date lies in the past. */
export function daysUntil(date: string, from: string): number {
  return Math.round((Date.parse(`${date}T00:00:00Z`) - Date.parse(`${from}T00:00:00Z`)) / 86_400_000);
}

/** An open task whose contractual due date has passed. A planned work day never makes a task overdue. */
export function isOverdue(task: TaskDto, today: string): boolean {
  return task.status === "OPEN" && !!task.dueDate && task.dueDate < today;
}

/** Cancellation deadlines of active contracts from today up to the window, soonest first. */
export function upcomingDeadlines(
  items: ContractDeadlineItemDto[],
  today: string,
  window = DEADLINE_WINDOW_DAYS,
): ContractDeadlineItemDto[] {
  return items
    .filter((item) => item.status === "ACTIVE")
    .filter((item) => {
      const days = daysUntil(item.cancellationDeadline, today);
      return days >= 0 && days <= window;
    })
    .sort((a, b) => a.cancellationDeadline.localeCompare(b.cancellationDeadline));
}

export type TodayBucket = "overdue" | "dueToday" | "plannedToday" | "upcoming" | "unassigned";

/**
 * The buckets of Today without the cancellation reviews whose deadline Today already shows with its countdown, so
 * the same contract does not appear twice. The review task stays in the task list and on its contract.
 */
export function todayBuckets(
  today: TodayResponse,
  shownDeadlines: ContractDeadlineItemDto[],
): Record<TodayBucket, TaskDto[]> {
  const shown = new Set(shownDeadlines.map((item) => item.contractId));
  const keep = (tasks: TaskDto[]) => tasks.filter((task) => !task.cancellationReview || !shown.has(task.cancellationReview.contractId));
  return {
    overdue: keep(today.overdue),
    dueToday: keep(today.dueToday),
    plannedToday: keep(today.plannedToday),
    upcoming: keep(today.upcoming),
    unassigned: keep(today.unassigned),
  };
}

/** Who a task list shows: everybody's tasks, one member's, or the tasks nobody is responsible for. */
export const EVERYONE = "all";
export const NOBODY = "nobody";

export function filterByOwner(tasks: TaskDto[], owner: string): TaskDto[] {
  if (owner === EVERYONE) return tasks;
  if (owner === NOBODY) return tasks.filter((task) => !task.ownerId);
  return tasks.filter((task) => task.ownerId === owner);
}

/** Open tasks first, then by due date (tasks without one last), planned day and title. */
export function sortTasks(tasks: TaskDto[]): TaskDto[] {
  const rank = (task: TaskDto) => (task.status === "OPEN" ? 0 : 1);
  return [...tasks].sort(
    (a, b) =>
      rank(a) - rank(b) ||
      (a.dueDate ?? "9999-12-31").localeCompare(b.dueDate ?? "9999-12-31") ||
      (a.plannedDate ?? "9999-12-31").localeCompare(b.plannedDate ?? "9999-12-31") ||
      a.title.localeCompare(b.title),
  );
}

/** The initial of a member for the small owner box; the name itself is read to screen readers. */
export function initial(name: string): string {
  return Array.from(name.trim())[0]?.toLocaleUpperCase() ?? "?";
}
