// @vitest-environment jsdom
import { cleanup, fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { MemoryRouter } from "react-router";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import type { TaskDto } from "../api/types";
import i18n from "../i18n";
import type { HistoryEntry, HistoryPage } from "../lib/history";
import { ChangeHistory } from "./ChangeHistory";
import { TaskDetail } from "./tasks/TaskDetail";

const api = vi.hoisted(() => ({ GET: vi.fn(), POST: vi.fn(), PUT: vi.fn() }));
vi.mock("../api/client", async (original) => ({ ...(await original<typeof import("../api/client")>()), api }));

const names: Record<string, string> = { "m-lisa": "Lisa", "m-cedric": "Cedric" };
vi.mock("../auth/SessionContext", () => ({
  useSession: () => ({ members: [], memberName: (id?: string | null) => (id ? names[id] : undefined) }),
}));

beforeEach(async () => {
  await i18n.changeLanguage("de");
});
afterEach(() => {
  cleanup();
  vi.resetAllMocks();
});

const ok = (data: unknown) => ({ data, response: new Response(null, { status: 200 }) });
const problem = (status: number, code: string) => ({ error: { code }, response: new Response(null, { status }) });

const task = (fields: Partial<TaskDto> = {}) =>
  ({
    id: "t1",
    title: "Stromabschlag überweisen",
    status: "OPEN",
    version: 4,
    ownerId: "m-cedric",
    dueDate: null,
    plannedDate: null,
    note: "",
    source: null,
    cancellationReview: null,
    trash: null,
    createdAt: "2026-10-01T08:00:00Z",
    ...fields,
  }) as unknown as TaskDto;

const entry = (fields: Partial<HistoryEntry> = {}): HistoryEntry => ({
  id: "c1",
  occurredAt: "2026-10-20T09:30:00Z",
  actorKind: "MEMBER",
  actorMemberId: "m-lisa",
  action: "ASSIGNED",
  changes: [{ field: "owner", before: "m-lisa", after: "m-cedric" }],
  undoable: true,
  ...fields,
});

function renderTask(current: TaskDto, onChange = vi.fn()) {
  render(
    <MemoryRouter>
      <TaskDetail task={current} onChange={onChange} heading={(title) => <h1>{title}</h1>} />
    </MemoryRouter>,
  );
  return onChange;
}

function openHistory() {
  const history = screen.getByTestId("change-history") as HTMLDetailsElement;
  history.open = true;
  fireEvent(history, new Event("toggle"));
}

describe("the history of a task", () => {
  it("loads when opened and says who changed what in words", async () => {
    api.GET.mockResolvedValue(
      ok({ taskId: "t1", recordingSince: "2026-09-01T00:00:00Z", changes: [entry()], nextBeforeVersion: null }),
    );
    renderTask(task());
    expect(api.GET).not.toHaveBeenCalled();

    openHistory();

    const item = await screen.findByTestId("history-entry");
    expect(api.GET).toHaveBeenCalledWith("/api/tasks/{id}/history", { params: { path: { id: "t1" }, query: {} } });
    expect(item.textContent).toContain("Lisa");
    expect(item.textContent).toContain("Zuständigkeit geändert");
    expect(item.textContent).toContain("Lisa → Cedric");
  });

  it("undoes with the version the member saw and shows the result", async () => {
    const undone = task({ version: 5, ownerId: "m-lisa" });
    api.GET.mockResolvedValue(
      ok({ taskId: "t1", recordingSince: "2026-09-01T00:00:00Z", changes: [entry()], nextBeforeVersion: null }),
    );
    api.POST.mockResolvedValue(ok(undone));
    const onChange = renderTask(task());
    openHistory();

    fireEvent.click(await screen.findByTestId("history-undo"));

    await waitFor(() => expect(onChange).toHaveBeenCalledWith(undone));
    expect(api.POST).toHaveBeenCalledWith("/api/tasks/{id}/history/{changeId}/undo", {
      params: { path: { id: "t1", changeId: "c1" } },
      body: { expectedVersion: 4 },
    });
  });

  it("explains a refused undo and shows the current task", async () => {
    const fresh = task({ version: 6 });
    api.GET.mockImplementation((path: string) =>
      Promise.resolve(
        path === "/api/tasks/{id}"
          ? ok(fresh)
          : ok({ taskId: "t1", recordingSince: "2026-09-01T00:00:00Z", changes: [entry()], nextBeforeVersion: null }),
      ),
    );
    api.POST.mockResolvedValue(problem(409, "history.undo.changedSince"));
    const onChange = renderTask(task());
    openHistory();

    fireEvent.click(await screen.findByTestId("history-undo"));

    expect((await screen.findByRole("alert")).textContent).toContain("inzwischen wieder geändert");
    expect(onChange).toHaveBeenCalledWith(fresh);
  });

  it("offers no undo for a task in the trash", async () => {
    api.GET.mockResolvedValue(
      ok({ taskId: "t1", recordingSince: "2026-09-01T00:00:00Z", changes: [entry()], nextBeforeVersion: null }),
    );
    renderTask(task({ trash: { trashedBy: "m-lisa", trashedAt: "2026-10-21T08:00:00Z", restorableUntil: "2026-11-20" } } as Partial<TaskDto>));
    openHistory();

    await screen.findByTestId("history-entry");
    expect(screen.queryByTestId("history-undo")).toBeNull();
  });
});

describe("the history panel", () => {
  const page = (fields: Partial<HistoryPage>): HistoryPage => ({
    recordingSince: "2026-09-01T00:00:00Z",
    changes: [],
    next: null,
    ...fields,
  });

  function renderPanel(load: (next: number | null) => Promise<HistoryPage>, createdAt = "2026-10-01T08:00:00Z") {
    render(
      <ChangeHistory
        refreshKey="1"
        createdAt={createdAt}
        load={load}
        undo={vi.fn()}
        formatValue={(_field, value) => value ?? "—"}
        labelName={(id) => ({ l1: "Versicherung", l2: "Auto" })[id] ?? "?"}
      />,
    );
    openHistory();
  }

  it("names an automation with the member who triggered it, and labels one by one", async () => {
    renderPanel(async () =>
      page({
        changes: [
          entry({
            id: "c2",
            actorKind: "AUTOMATION",
            actorReference: "contractDeadlines",
            action: "CONTRACT_UPDATED",
            undoable: false,
            changes: [{ field: "dueDate", before: "2026-11-01", after: "2026-12-01" }],
          }),
          entry({ id: "c3", action: "LABELS_CHANGED", changes: [{ field: "labels", before: "l1", after: "l2" }] }),
        ],
      }),
    );

    const [automatic, labels] = await screen.findAllByTestId("history-entry");
    expect(automatic.textContent).toContain("Vertragsfristen (ausgelöst von Lisa)");
    expect(within(automatic).queryByTestId("history-undo")).toBeNull();
    expect(labels.textContent).toContain("+ Auto");
    expect(labels.textContent).toContain("− Versicherung");
  });

  it("pages older entries and says when recording began for an older record", async () => {
    const load = vi.fn(async (next: number | null) =>
      next == null ? page({ changes: [entry({ id: "c9" })], next: 9 }) : page({ changes: [entry({ id: "c1" })] }),
    );
    renderPanel(load, "2026-08-01T08:00:00Z");

    fireEvent.click(await screen.findByText("Ältere Änderungen"));

    await waitFor(() => expect(screen.getAllByTestId("history-entry")).toHaveLength(2));
    expect(load).toHaveBeenLastCalledWith(9);
    expect(screen.getByText(/wurden nicht aufgezeichnet/)).toBeTruthy();
  });

  it("says when nothing was recorded yet", async () => {
    renderPanel(async () => page({}));

    expect(await screen.findByText("Noch keine Änderungen aufgezeichnet.")).toBeTruthy();
  });
});
