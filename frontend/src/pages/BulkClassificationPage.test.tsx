// @vitest-environment jsdom
import { cleanup, fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { MemoryRouter } from "react-router";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import i18n from "../i18n";
import { BulkClassificationPage } from "./BulkClassificationPage";

const api = vi.hoisted(() => ({ GET: vi.fn(), POST: vi.fn(), PUT: vi.fn(), DELETE: vi.fn() }));
vi.mock("../api/client", async (original) => ({ ...(await original<typeof import("../api/client")>()), api }));

vi.mock("../auth/SessionContext", () => ({
  useSession: () => ({ persons: [], members: [], memberName: () => "Lisa" }),
}));

beforeEach(async () => {
  await i18n.changeLanguage("de");
});
afterEach(() => {
  cleanup();
  vi.resetAllMocks();
});

const ok = (data: unknown, status = 200) => ({ data, response: new Response(null, { status }) });
const problem = (code: string, status = 400) => ({
  error: { code, status, title: code },
  response: new Response(null, { status }),
});

const cost = {
  perDocument: 0.01,
  basis: "AVERAGE",
  samples: 20,
  total: 0.12,
  maximum: 0.6,
  dailyLimit: 0.25,
  spentToday: 0.05,
  remainingToday: 0.2,
  documentsToday: 12,
  days: 1,
  currency: "USD",
  nextBudgetDay: "2026-10-01T22:00:00Z",
};
const preview = {
  scope: { kind: "ALL", includeSubfolders: false, folderPath: [] },
  documents: 12,
  limited: false,
  maxDocuments: 500,
  sample: [{ id: "d1", title: "Stromrechnung 2024" }],
  cost,
  refusal: null,
  runningId: null,
};
const run = {
  id: "b1",
  state: "RUNNING",
  stopCode: null,
  scope: { kind: "ALL", includeSubfolders: false, folderPath: [] },
  requestedBy: "m-lisa",
  requestedAt: "2026-10-01T08:00:00Z",
  cancelledBy: null,
  cancelledAt: null,
  finishedAt: null,
  total: 12,
  pending: 7,
  inFlight: 2,
  classified: 3,
  needsReview: 1,
  failed: 0,
  notClassified: 0,
  skipped: 0,
  cancelled: 0,
  superseded: 0,
  waitingForBudgetUntil: null,
  placementGroups: [],
  failures: [],
  skips: [],
  reviewDocuments: [{ id: "d2", title: "Versicherungsschein" }],
};

function answer(runs: () => unknown[]) {
  api.GET.mockImplementation((path: string) => {
    if (path === "/api/documents/bulk-analyses") return Promise.resolve(ok(runs()));
    if (path === "/api/categories") return Promise.resolve(ok({ categories: [], unsortedCount: 0, totalDocuments: 12 }));
    return Promise.resolve(ok([]));
  });
}

function renderPage(path = "/documents/classify") {
  render(
    <MemoryRouter initialEntries={[path]}>
      <BulkClassificationPage />
    </MemoryRouter>,
  );
}

describe("bulk classification", () => {
  it("shows the preview with its cost and starts only on the member's click, bound to the previewed count", async () => {
    let runs: unknown[] = [];
    answer(() => runs);
    api.POST.mockImplementation((path: string) => {
      if (path === "/api/documents/bulk-analyses/preview") return Promise.resolve(ok(preview));
      runs = [run];
      return Promise.resolve(ok(run));
    });
    renderPage();

    expect((await screen.findByTestId("bulk-preview-count")).textContent).toBe("12 Dokumente wurden noch nie eingeordnet.");
    const costText = screen.getByTestId("bulk-cost").textContent ?? "";
    expect(costText).toContain("Geschätzt 0,12");
    expect(costText).toContain("Mittelwert der letzten 20 Einordnungen");
    expect(costText).toContain("reicht heute für etwa 12 Dokumente");
    expect(costText).toContain("Passt voraussichtlich in das heutige KI-Budget.");
    expect(api.POST).toHaveBeenCalledTimes(1);
    expect(api.POST).toHaveBeenCalledWith("/api/documents/bulk-analyses/preview", { body: { kind: "ALL", includeSubfolders: false } });

    fireEvent.click(screen.getByTestId("bulk-start-button"));
    await screen.findByTestId("bulk-run");
    expect(api.POST).toHaveBeenCalledWith("/api/documents/bulk-analyses", {
      body: { scope: { kind: "ALL", includeSubfolders: false }, expectedDocuments: 12 },
    });
    expect(screen.getByTestId("bulk-progress").textContent).toBe("3 von 12 erledigt");
    expect(screen.queryByTestId("bulk-start")).toBeNull();
    expect(screen.getByTestId("bulk-review").textContent).toContain("Versicherungsschein");
  });

  it("explains why a member without AI access cannot start it", async () => {
    answer(() => []);
    api.POST.mockResolvedValue(ok({ ...preview, refusal: "ai.provider.memberNotAllowed" }));
    renderPage();

    expect((await screen.findByTestId("bulk-refusal")).textContent).toContain("für dein Konto nicht freigegeben");
    expect((screen.getByTestId("bulk-start-button") as HTMLButtonElement).disabled).toBe(true);
  });

  it("shows a refused start because the documents changed and loads the new preview", async () => {
    answer(() => []);
    api.POST.mockImplementation((path: string) =>
      Promise.resolve(path === "/api/documents/bulk-analyses/preview" ? ok(preview) : problem("analysis.bulk.changed")),
    );
    renderPage();

    fireEvent.click(await screen.findByTestId("bulk-start-button"));
    expect((await screen.findByRole("alert")).textContent).toContain("haben sich geändert");
    await waitFor(() => expect(api.POST.mock.calls.filter(([path]) => path === "/api/documents/bulk-analyses/preview")).toHaveLength(2));
  });

  it("cancels a running run after a confirmation", async () => {
    let runs: unknown[] = [run];
    answer(() => runs);
    api.POST.mockImplementation((path: string) => {
      if (path === "/api/documents/bulk-analyses/preview") return Promise.resolve(ok(preview));
      runs = [{ ...run, state: "CANCELLED", pending: 0, inFlight: 0, cancelled: 9, cancelledBy: "m-ben", cancelledAt: "2026-10-01T09:00:00Z", finishedAt: "2026-10-01T09:00:00Z" }];
      return Promise.resolve(ok(runs[0]));
    });
    renderPage();

    fireEvent.click(await screen.findByTestId("bulk-cancel"));
    const dialog = screen.getByRole("dialog");
    expect(within(dialog).getByText("Einordnung abbrechen?")).toBeTruthy();
    fireEvent.click(within(dialog).getByTestId("bulk-cancel-confirm"));

    await waitFor(() => expect(screen.getByTestId("bulk-run").getAttribute("data-state")).toBe("CANCELLED"));
    expect(api.POST).toHaveBeenCalledWith("/api/documents/bulk-analyses/{id}/cancel", { params: { path: { id: "b1" } } });
    expect(screen.getByTestId("bulk-run").textContent).toContain("Abgebrochen von Lisa");
  });

  it("groups the results of a finished run: budget waits, folder proposals, failures and skips", async () => {
    answer(() => [
      {
        ...run,
        state: "COMPLETED",
        pending: 0,
        inFlight: 0,
        classified: 9,
        failed: 2,
        skipped: 1,
        finishedAt: "2026-10-02T08:00:00Z",
        placementGroups: [{ key: "path::verträge", path: ["Verträge", "Internet"], newFolders: ["Internet"], documents: 4, example: { id: "d3", title: "Telekom" } }],
        failures: [{ code: "provider.unavailable", documents: 2, examples: [{ id: "d4", title: "Scan 12" }] }],
        skips: [{ code: "document.trashed", documents: 1, examples: [{ id: "d5", title: "Alt" }] }],
      },
    ]);
    api.POST.mockResolvedValue(ok(preview));
    renderPage("/documents/classify?run=b1");

    const placements = await screen.findByTestId("bulk-placements");
    expect(placements.textContent).toContain("Verträge / Internet: 4 Dokumente");
    expect(placements.textContent).toContain("Neue Ordner: Internet");
    expect(within(placements).getByRole("link", { name: "Vorschlag prüfen" }).getAttribute("href")).toBe("/documents/d3");
    expect(screen.getByTestId("bulk-failures").textContent).toContain("2 Dokumente: der KI-Dienst war vorübergehend nicht erreichbar");
    expect(screen.getByTestId("bulk-skips").textContent).toContain("1 Dokument: im Papierkorb");
    expect(screen.getByTestId("bulk-run").textContent).toContain("Fertig");
  });

  it("shows a run waiting for the next budget day as waiting, not failed", async () => {
    answer(() => [{ ...run, waitingForBudgetUntil: "2026-10-01T22:00:00Z" }]);
    renderPage();

    expect((await screen.findByTestId("bulk-waiting")).textContent).toContain("Es geht am");
    expect(screen.queryByTestId("bulk-failures")).toBeNull();
  });
});
