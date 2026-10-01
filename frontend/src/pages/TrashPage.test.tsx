// @vitest-environment jsdom
import { cleanup, fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { MemoryRouter } from "react-router";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import i18n from "../i18n";
import { TrashPage } from "./TrashPage";

const api = vi.hoisted(() => ({ GET: vi.fn(), POST: vi.fn(), PUT: vi.fn(), DELETE: vi.fn() }));
vi.mock("../api/client", async (original) => ({ ...(await original<typeof import("../api/client")>()), api }));

vi.mock("../auth/SessionContext", () => ({
  useSession: () => ({ persons: [], members: [], memberName: () => "Lisa" }),
}));

vi.mock("../components/shell/ContextPanel", () => ({
  ContextPanel: () => null,
  usePanelShown: () => false,
}));

beforeEach(async () => {
  await i18n.changeLanguage("de");
});
afterEach(() => {
  cleanup();
  vi.resetAllMocks();
});

const ok = (data: unknown, status = 200) => ({ data, response: new Response(null, { status }) });
const trash = { trashedBy: "m-lisa", trashedAt: "2026-09-30T08:00:00Z", restorableUntil: "2026-10-30T08:00:00Z" };
const scan = { id: "d1", title: "Falscher Scan", version: 4, trash };
const task = { id: "t1", title: "Doppelte Aufgabe", version: 2, trash };

function answer(documents: () => unknown[], tasks: () => unknown[] = () => []) {
  api.GET.mockImplementation((path: string) => {
    if (path === "/api/documents/trash") return Promise.resolve(ok(documents()));
    if (path === "/api/tasks/trash") return Promise.resolve(ok(tasks()));
    return Promise.resolve(ok([]));
  });
}

function renderPage() {
  render(
    <MemoryRouter>
      <TrashPage />
    </MemoryRouter>,
  );
}

describe("trash permanent deletion", () => {
  it("asks in a danger dialog first and does nothing when cancelled", async () => {
    answer(() => [scan]);
    renderPage();

    fireEvent.click(await screen.findByTestId("trash-entry-purge"));
    const dialog = screen.getByRole("dialog");
    expect(within(dialog).getByText("„Falscher Scan“ endgültig löschen?")).toBeTruthy();
    expect(within(dialog).getByText(/auch das Original/)).toBeTruthy();
    expect(within(dialog).getByText("Nicht umkehrbar")).toBeTruthy();
    fireEvent.click(within(dialog).getByRole("button", { name: "Abbrechen" }));

    expect(api.POST).not.toHaveBeenCalled();
  });

  it("starts a document's deletion and shows it as running instead of offering a restore", async () => {
    let purging = false;
    answer(() => [purging ? { ...scan, version: 5, trash: { ...trash, purge: { state: "PENDING", requestedBy: "m-lisa", requestedAt: "2026-10-01T08:00:00Z" } } } : scan]);
    api.POST.mockImplementation(() => {
      purging = true;
      return Promise.resolve(ok(scan, 202));
    });
    renderPage();

    fireEvent.click(await screen.findByTestId("trash-entry-purge"));
    fireEvent.click(screen.getByTestId("trash-purge-confirm"));

    await screen.findByTestId("trash-entry-purging");
    expect(api.POST).toHaveBeenCalledWith("/api/documents/{id}/purge", { params: { path: { id: "d1" } }, body: { expectedVersion: 4 } });
    expect(screen.getByTestId("trash-purge-toast").textContent).toContain("„Falscher Scan“ wird jetzt endgültig gelöscht.");
    expect(screen.queryByTestId("trash-entry-restore")).toBeNull();
    expect(screen.queryByTestId("trash-entry-purge")).toBeNull();
  });

  it("deletes a task at once and offers a failed document deletion again without a second dialog", async () => {
    let tasks = [task];
    const failed = { ...scan, trash: { ...trash, purge: { state: "FAILED", requestedBy: "m-lisa", requestedAt: "2026-10-01T08:00:00Z", failureCode: "paperless.rejected" } } };
    answer(() => [failed], () => tasks);
    api.POST.mockImplementation((path: string) => {
      if (path === "/api/tasks/{id}/purge") tasks = [];
      return Promise.resolve(ok(undefined, path === "/api/tasks/{id}/purge" ? 204 : 202));
    });
    renderPage();

    await screen.findByTestId("trash-entry-purge-failed");
    const taskEntry = (await screen.findAllByTestId("trash-entry")).find((entry) => entry.dataset.kind === "task")!;
    fireEvent.click(within(taskEntry).getByTestId("trash-entry-purge"));
    fireEvent.click(screen.getByTestId("trash-purge-confirm"));
    await waitFor(() => expect(screen.getByTestId("trash-purge-toast").textContent).toContain("„Doppelte Aufgabe“ wurde endgültig gelöscht."));
    expect(api.POST).toHaveBeenCalledWith("/api/tasks/{id}/purge", { params: { path: { id: "t1" } }, body: { expectedVersion: 2 } });

    fireEvent.click(screen.getByTestId("trash-entry-purge-retry"));
    await waitFor(() => expect(api.POST).toHaveBeenCalledWith("/api/documents/{id}/purge", { params: { path: { id: "d1" } }, body: { expectedVersion: 4 } }));
  });

  it("names the conflict when the deletion is refused", async () => {
    answer(() => [scan]);
    api.POST.mockResolvedValue({
      error: { code: "document.purge.archiving" },
      response: new Response(null, { status: 409 }),
    });
    renderPage();

    fireEvent.click(await screen.findByTestId("trash-entry-purge"));
    fireEvent.click(screen.getByTestId("trash-purge-confirm"));

    expect(await screen.findByText("Das Dokument wird gerade noch archiviert. Versuche es in einem Moment noch einmal.")).toBeTruthy();
  });

  it("keeps a restore conflict visible after the list reloads", async () => {
    answer(() => [scan]);
    api.POST.mockResolvedValue({ error: { code: "version.conflict" }, response: new Response(null, { status: 409 }) });
    renderPage();

    fireEvent.click(await screen.findByTestId("trash-entry-restore"));

    expect(await screen.findByText(/Das wurde inzwischen geändert/)).toBeTruthy();
    await waitFor(() => expect(api.GET.mock.calls.filter(([path]) => path === "/api/documents/trash")).toHaveLength(2));
    expect(screen.getByText(/Das wurde inzwischen geändert/)).toBeTruthy();
  });
});
