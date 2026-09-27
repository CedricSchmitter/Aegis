// @vitest-environment jsdom
import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { MemoryRouter } from "react-router";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import type { TaskDto } from "../api/types";
import i18n from "../i18n";
import { TaskDetail } from "./tasks/TaskDetail";
import { useTrashUndo } from "./TrashControls";

const api = vi.hoisted(() => ({ GET: vi.fn(), POST: vi.fn(), PUT: vi.fn() }));
vi.mock("../api/client", async (original) => ({ ...(await original<typeof import("../api/client")>()), api }));

vi.mock("../auth/SessionContext", () => ({
  useSession: () => ({ members: [], memberName: () => "Lisa" }),
}));

beforeEach(async () => {
  await i18n.changeLanguage("de");
});
afterEach(() => {
  cleanup();
  vi.resetAllMocks();
});

const trash = { trashedBy: "m-lisa", trashedAt: "2026-09-27T08:00:00Z", restorableUntil: "2026-10-27" };

const task = (fields: Partial<TaskDto> = {}) =>
  ({
    id: "t1",
    title: "Stromabschlag überweisen",
    status: "OPEN",
    version: 4,
    ownerId: null,
    dueDate: null,
    plannedDate: null,
    note: "",
    source: null,
    cancellationReview: null,
    trash: null,
    ...fields,
  }) as unknown as TaskDto;

const ok = (data: unknown) => ({ data, response: new Response(null, { status: 200 }) });

function Harness({ undo }: { undo: () => void }) {
  const { notify, toast } = useTrashUndo<TaskDto>();
  return (
    <>
      <button type="button" onClick={() => notify(task({ version: 5, trash }), undo)}>
        trash
      </button>
      {toast}
    </>
  );
}

describe("moving to the trash", () => {
  it("acts at once and hands the trashed task to the page for the undo message", async () => {
    const trashed = task({ version: 5, trash });
    api.POST.mockResolvedValue(ok(trashed));
    const onChange = vi.fn();
    const onTrashed = vi.fn();
    render(
      <MemoryRouter>
        <TaskDetail task={task()} onChange={onChange} onTrashed={onTrashed} heading={(title) => <h1>{title}</h1>} />
      </MemoryRouter>,
    );

    fireEvent.click(screen.getByTestId("trash-task"));

    await waitFor(() => expect(onTrashed).toHaveBeenCalledWith(trashed));
    expect(api.POST).toHaveBeenCalledWith("/api/tasks/{id}/trash", { params: { path: { id: "t1" } }, body: { expectedVersion: 4 } });
    expect(onChange).toHaveBeenCalledWith(trashed);
    expect(screen.queryByTestId("trash-task-confirm")).toBeNull();
  });

  it("says until when the record stays restorable and offers undo", () => {
    const undo = vi.fn();
    render(<Harness undo={undo} />);

    fireEvent.click(screen.getByRole("button", { name: "trash" }));

    const toast = screen.getByTestId("trash-toast");
    expect(toast.textContent).toContain("Im Papierkorb");
    expect(toast.textContent).toContain("27.10.2026");
    fireEvent.click(screen.getByRole("button", { name: "Rückgängig" }));
    expect(undo).toHaveBeenCalledTimes(1);
    expect(screen.queryByTestId("trash-toast")).toBeNull();
  });
});
