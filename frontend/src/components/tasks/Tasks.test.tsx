// @vitest-environment jsdom
import { cleanup, fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { MemoryRouter } from "react-router";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import type { ContractSummaryDto, TaskDto } from "../../api/types";
import i18n from "../../i18n";
import { todayIso } from "../../lib/format";
import { ContractCard } from "../contracts/ContractCard";
import { TaskCard } from "./TaskCard";
import { TaskDetail } from "./TaskDetail";

const api = vi.hoisted(() => ({ GET: vi.fn(), POST: vi.fn(), PUT: vi.fn() }));
vi.mock("../../api/client", async (original) => ({ ...(await original<typeof import("../../api/client")>()), api }));

vi.mock("../../auth/SessionContext", () => ({
  useSession: () => ({
    members: [
      { id: "m-lisa", displayName: "Lisa" },
      { id: "m-cedric", displayName: "Cedric" },
    ],
    memberName: (id: string | null | undefined) => ({ "m-lisa": "Lisa", "m-cedric": "Cedric" })[id ?? ""] ?? null,
  }),
}));

beforeEach(async () => {
  await i18n.changeLanguage("de");
});
afterEach(() => {
  cleanup();
  vi.resetAllMocks();
});

/** An ISO date `days` from today, so the tests do not depend on the calendar. */
const inDays = (days: number) => {
  const date = new Date(`${todayIso()}T00:00:00Z`);
  date.setUTCDate(date.getUTCDate() + days);
  return date.toISOString().substring(0, 10);
};

const task = (fields: Partial<TaskDto> = {}) =>
  ({
    id: "t1",
    title: "Stromabschlag überweisen",
    status: "OPEN",
    version: 4,
    ownerId: "m-lisa",
    dueDate: inDays(5),
    plannedDate: null,
    note: "",
    source: null,
    cancellationReview: null,
    trash: null,
    ...fields,
  }) as unknown as TaskDto;

const ok = (data: unknown) => ({ data, response: new Response(null, { status: 200 }) });

const inRouter = (ui: React.ReactNode) => render(<MemoryRouter>{ui}</MemoryRouter>);

describe("task card", () => {
  it("keeps the deadline and the planned work day apart and marks an overdue task red", () => {
    inRouter(
      <ul>
        <TaskCard task={task({ dueDate: inDays(-2), plannedDate: inDays(1) })} onChange={vi.fn()} />
      </ul>,
    );
    const card = screen.getByTestId("task-item");
    expect(card.className).toContain("card-danger");
    const overdue = card.querySelector('[data-state="OVERDUE"]');
    expect(overdue?.textContent).toMatch(/^Überfällig seit /);
    expect(within(card).getByText(/^Geplant /).className).toContain("text-data");
    expect(within(card).getByTitle("Lisa").textContent).toContain("L");
  });

  it("completes the task at once with the version the member saw", async () => {
    const done = task({ status: "DONE", version: 5 });
    api.POST.mockResolvedValue(ok(done));
    const onChange = vi.fn();
    const onCompleted = vi.fn();
    inRouter(
      <ul>
        <TaskCard task={task()} onChange={onChange} onCompleted={onCompleted} />
      </ul>,
    );
    fireEvent.click(screen.getByRole("checkbox", { name: "Als erledigt markieren: Stromabschlag überweisen" }));
    await waitFor(() => expect(onCompleted).toHaveBeenCalledWith(done));
    expect(api.POST).toHaveBeenCalledWith("/api/tasks/{id}/complete", { params: { path: { id: "t1" } }, body: { expectedVersion: 4 } });
    expect(onChange).toHaveBeenCalledWith(done);
  });

  it("selects instead of opening while the context panel is shown", () => {
    const onSelect = vi.fn();
    inRouter(
      <ul>
        <TaskCard task={task()} onChange={vi.fn()} onSelect={onSelect} selected />
      </ul>,
    );
    const link = screen.getByRole("link", { name: "Stromabschlag überweisen" });
    expect(link.getAttribute("aria-current")).toBe("true");
    expect(screen.getByTestId("task-item").getAttribute("data-selected")).toBe("true");
    fireEvent.click(link, { detail: 1 });
    expect(onSelect).toHaveBeenCalledWith("t1");
  });
});

describe("task detail", () => {
  const heading = (title: string) => <h1>{title}</h1>;

  it("makes Done the primary action and reschedules only the planned work day", async () => {
    api.POST.mockResolvedValue(ok(task({ plannedDate: inDays(3), version: 5 })));
    inRouter(<TaskDetail task={task()} onChange={vi.fn()} heading={heading} />);
    expect(screen.getByTestId("task-complete").className).toContain("btn-primary");
    fireEvent.click(screen.getByTestId("task-plan"));
    fireEvent.change(screen.getByTestId("task-date-input"), { target: { value: inDays(3) } });
    fireEvent.click(screen.getByRole("button", { name: "Speichern" }));
    await waitFor(() => expect(api.POST).toHaveBeenCalled());
    expect(api.POST).toHaveBeenCalledWith("/api/tasks/{id}/plan", {
      params: { path: { id: "t1" } },
      body: { plannedDate: inDays(3), expectedVersion: 4 },
    });
  });

  it("offers no way to move the contractual deadline of a cancellation review", () => {
    const review = task({
      title: "Handyvertrag",
      dueDate: inDays(10),
      cancellationReview: {
        contractId: "c1",
        contractName: "Handyvertrag",
        cancellationDeadline: inDays(10),
        contractEnd: null,
        contractVersion: 2,
        reminderDate: inDays(3),
      },
    });
    inRouter(<TaskDetail task={review} onChange={vi.fn()} heading={heading} />);
    expect(screen.getByRole("heading", { level: 1 }).textContent).toBe("Handyvertrag - Kündigung prüfen");
    expect(screen.queryByTestId("task-change-due")).toBeNull();
    expect(screen.getByTestId("task-plan")).toBeTruthy();
    expect(screen.getByTestId("cancellation-review-details").getAttribute("href")).toBe("/contracts/c1");
  });
});

describe("contract card", () => {
  const contract = (deadline: string | null, status = "ACTIVE") =>
    ({
      id: "c1",
      name: "Handyvertrag",
      counterparty: "Funkwelle",
      status,
      pendingFacts: 1,
      conflicts: 0,
      deadline: { determined: deadline !== null, deadline, basedOn: [], missing: [], contradicting: [] },
    }) as unknown as ContractSummaryDto;

  const card = (summary: ContractSummaryDto) => {
    inRouter(
      <ul>
        <ContractCard contract={summary} on={todayIso()} />
      </ul>,
    );
    return screen.getByTestId("contract-item");
  };

  it("highlights a deadline within 30 days with the countdown in the action colour", () => {
    const item = card(contract(inDays(18)));
    expect(item.className).toContain("card-attention");
    expect(item.querySelector(".ui-countdown")?.getAttribute("data-tone")).toBe("attention");
    expect(item.querySelector(".ui-countdown b")?.textContent).toBe("18");
  });

  it("keeps a later deadline quiet and shows no state badge while the contract is active", () => {
    const item = card(contract(inDays(60)));
    expect(item.className).not.toContain("card-attention");
    expect(item.querySelector(".ui-countdown")?.getAttribute("data-tone")).toBe("neutral");
    expect(item.querySelector("[data-state]")).toBeNull();
  });

  it("names the status of a cancelled contract and counts nothing down", () => {
    const item = card(contract(inDays(10), "CANCELLED"));
    expect(item.querySelector(".ui-countdown")).toBeNull();
    expect(item.querySelector('[data-state="CANCELLED"]')?.textContent).toBe("Gekündigt");
  });
});
