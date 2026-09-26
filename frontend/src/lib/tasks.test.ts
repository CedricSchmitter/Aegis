import { describe, expect, it } from "vitest";
import type { ContractDeadlineItemDto, TaskDto, TodayResponse } from "../api/types";
import { NOBODY, daysUntil, filterByOwner, initial, isOverdue, sortTasks, todayBuckets, upcomingDeadlines } from "./tasks";

const task = (id: string, fields: Partial<TaskDto> = {}) =>
  ({ id, title: id, status: "OPEN", dueDate: null, plannedDate: null, ownerId: null, ...fields }) as unknown as TaskDto;

const deadline = (contractId: string, date: string, status = "ACTIVE") =>
  ({ contractId, name: contractId, cancellationDeadline: date, status }) as unknown as ContractDeadlineItemDto;

describe("daysUntil", () => {
  it("counts whole calendar days, also across the change to winter time", () => {
    expect(daysUntil("2026-09-26", "2026-09-26")).toBe(0);
    expect(daysUntil("2026-10-26", "2026-09-26")).toBe(30);
    expect(daysUntil("2026-09-24", "2026-09-26")).toBe(-2);
    expect(daysUntil("2026-10-26", "2026-10-24")).toBe(2);
  });
});

describe("isOverdue", () => {
  it("looks at the contractual due date only, never at the planned day", () => {
    expect(isOverdue(task("a", { dueDate: "2026-09-25" }), "2026-09-26")).toBe(true);
    expect(isOverdue(task("b", { dueDate: "2026-09-26" }), "2026-09-26")).toBe(false);
    expect(isOverdue(task("c", { plannedDate: "2026-09-01" }), "2026-09-26")).toBe(false);
    expect(isOverdue(task("d", { dueDate: "2026-09-01", status: "DONE" }), "2026-09-26")).toBe(false);
  });
});

describe("upcomingDeadlines", () => {
  it("keeps active contracts from today to 30 days ahead, soonest first", () => {
    const items = [
      deadline("later", "2026-10-27"),
      deadline("edge", "2026-10-26"),
      deadline("today", "2026-09-26"),
      deadline("past", "2026-09-25"),
      deadline("cancelled", "2026-10-01", "CANCELLED"),
    ];
    expect(upcomingDeadlines(items, "2026-09-26").map((item) => item.contractId)).toEqual(["today", "edge"]);
  });
});

describe("todayBuckets", () => {
  it("leaves out the cancellation review of a deadline Today already counts down", () => {
    const review = (contractId: string) =>
      task(`review-${contractId}`, {
        dueDate: "2026-10-01",
        cancellationReview: { contractId } as TaskDto["cancellationReview"],
      });
    const today = {
      date: "2026-09-26",
      overdue: [task("late")],
      dueToday: [],
      plannedToday: [],
      upcoming: [review("shown"), review("other"), task("plain")],
      unassigned: [],
    } as unknown as TodayResponse;
    const buckets = todayBuckets(today, [deadline("shown", "2026-10-01")]);
    expect(buckets.upcoming.map((entry) => entry.id)).toEqual(["review-other", "plain"]);
    expect(buckets.overdue.map((entry) => entry.id)).toEqual(["late"]);
  });
});

describe("filterByOwner and sortTasks", () => {
  const tasks = [
    task("done", { status: "DONE", dueDate: "2026-09-01", ownerId: "m1" }),
    task("none", { dueDate: null }),
    task("late", { dueDate: "2026-09-20", ownerId: "m2" }),
    task("soon", { dueDate: "2026-09-28", ownerId: "m1" }),
  ];

  it("filters by the responsible member or by nobody", () => {
    expect(filterByOwner(tasks, "m1").map((entry) => entry.id)).toEqual(["done", "soon"]);
    expect(filterByOwner(tasks, NOBODY).map((entry) => entry.id)).toEqual(["none"]);
  });

  it("lists open tasks by due date first and finished ones last", () => {
    expect(sortTasks(tasks).map((entry) => entry.id)).toEqual(["late", "soon", "none", "done"]);
  });
});

describe("initial", () => {
  it("takes the first letter of a display name", () => {
    expect(initial("lisa")).toBe("L");
    expect(initial(" Élise")).toBe("É");
    expect(initial("")).toBe("?");
  });
});
