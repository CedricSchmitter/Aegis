// @vitest-environment jsdom
import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { MemoryRouter, Route, Routes } from "react-router";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import type { DocumentDto } from "../api/types";
import i18n from "../i18n";
import { DocumentDetailPage } from "./DocumentDetailPage";

const api = vi.hoisted(() => ({ GET: vi.fn(), POST: vi.fn(), PUT: vi.fn(), DELETE: vi.fn() }));
vi.mock("../api/client", async (original) => ({ ...(await original<typeof import("../api/client")>()), api }));

vi.mock("../auth/SessionContext", () => ({
  useSession: () => ({ persons: [], members: [], memberName: () => null }),
}));

vi.mock("../components/shell/ShellData", async (original) => ({
  ...(await original<typeof import("../components/shell/ShellData")>()),
  useDesktop: () => false,
}));

beforeEach(async () => {
  await i18n.changeLanguage("de");
});
afterEach(() => {
  cleanup();
  vi.resetAllMocks();
});

const ok = (data: unknown) => ({ data, response: new Response(null, { status: 200 }) });
const never = () => new Promise(() => undefined);

const document = {
  id: "d1",
  title: "Stromrechnung",
  titleOrigin: "MANUAL",
  contentType: "application/pdf",
  checksumSha256: "0",
  sizeBytes: 1024,
  originalAvailable: true,
  originalFilename: "strom.pdf",
  archivePdfAvailable: true,
  uploadedAt: "2026-09-20T10:00:00Z",
  uploadedBy: "m-lisa",
  version: 3,
  subjects: [],
  filename: { filename: "strom.pdf", source: "ORIGINAL" },
  processing: { state: "ARCHIVED", attempts: 1, retryScheduled: false, archivedAt: "2026-09-20T10:01:00Z" },
  analysis: { state: "NOT_REQUESTED", candidates: [], flags: [], generation: 0, history: [], subjects: [], documentDateStatus: "NONE", version: 0 },
  textRecognition: { state: "RECOGNIZED" },
  classification: {
    folderSubjects: [],
    labels: [],
    path: [],
    placement: { byMember: false, revertible: false },
    version: 0,
  },
} as unknown as DocumentDto;

function answer(documentReload: () => unknown) {
  let documentCalls = 0;
  api.GET.mockImplementation((path: string) => {
    if (path === "/api/documents/{id}") return documentCalls++ === 0 ? Promise.resolve(ok(document)) : documentReload();
    if (path === "/api/categories") return Promise.resolve(ok({ categories: [], unsortedCount: 0, totalDocuments: 1 }));
    if (path === "/api/documents/{id}/derivations") return Promise.resolve(ok({ origin: null, derived: [] }));
    return Promise.resolve(ok([]));
  });
}

function renderPage() {
  render(
    <MemoryRouter initialEntries={["/documents/d1"]}>
      <Routes>
        <Route path="/documents/:id" element={<DocumentDetailPage />} />
      </Routes>
    </MemoryRouter>,
  );
}

describe("document detail trash", () => {
  it("reports a concurrent change and frees the trash button even while the reload hangs", async () => {
    answer(never);
    api.POST.mockResolvedValue({
      error: { status: 409, code: "version.conflict" },
      response: new Response(null, { status: 409 }),
    });
    renderPage();
    const trash = await screen.findByTestId("trash-document");

    fireEvent.click(trash);

    expect((await screen.findByRole("alert")).textContent).toContain("inzwischen geändert");
    await waitFor(() => expect((screen.getByTestId("trash-document") as HTMLButtonElement).disabled).toBe(false));
    expect(api.POST).toHaveBeenCalledWith("/api/documents/{id}/trash", { params: { path: { id: "d1" } }, body: { expectedVersion: 3 } });
    expect(api.GET.mock.calls.filter(([path]) => path === "/api/documents/{id}")).toHaveLength(2);
  });
});
