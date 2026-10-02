// @vitest-environment jsdom
import "fake-indexeddb/auto";
import { act, cleanup, fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { MemoryRouter, Route, Routes } from "react-router";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import i18n from "../i18n";
import { clearPlan, loadPlan } from "../pageEdit/planStore";
import { PageEditorPage } from "./PageEditorPage";

const api = vi.hoisted(() => ({ GET: vi.fn(), POST: vi.fn(), PUT: vi.fn(), DELETE: vi.fn() }));
vi.mock("../api/client", async (original) => ({ ...(await original<typeof import("../api/client")>()), api }));

vi.mock("../auth/SessionContext", () => ({
  useSession: () => ({ member: { id: "m-lisa" }, persons: [{ id: "m-lisa" }], members: [], memberName: () => "Lisa" }),
}));

vi.mock("../components/shell/ShellData", async (original) => ({
  ...(await original<typeof import("../components/shell/ShellData")>()),
  useDesktop: () => false,
}));

const ok = (data: unknown) => ({ data, response: new Response(null, { status: 200 }) });
const fetchMock = vi.fn();

beforeEach(async () => {
  await i18n.changeLanguage("de");
  await clearPlan("m-lisa");
  vi.stubGlobal("fetch", fetchMock);
});
afterEach(() => {
  cleanup();
  vi.resetAllMocks();
  vi.unstubAllGlobals();
});

const documents: Record<string, { title: string; pages: number; refusal?: string; warnings?: string[] }> = {
  strom: { title: "Stromrechnung", pages: 3, warnings: ["SIGNED"] },
  geheim: { title: "Geheim", pages: 0, refusal: "pageEdit.source.encrypted" },
};

function answer(operation: () => unknown) {
  api.GET.mockImplementation((path: string, options: { params: { path: { id: string } } }) => {
    const id = options?.params?.path?.id;
    if (path === "/api/categories") return Promise.resolve(ok({ categories: [{ id: "f1", name: "Strom", parentId: null }] }));
    if (path === "/api/documents/{id}") {
      return Promise.resolve(
        ok({ id, title: documents[id].title, subjects: [{ kind: "HOUSE" }], classification: { categoryId: "f1" } }),
      );
    }
    if (path === "/api/documents/{id}/pages") {
      const entry = documents[id];
      return Promise.resolve(
        ok({
          documentId: id,
          sourceKind: "PDF",
          pages: Array.from({ length: entry.pages }, (_, index) => ({ index, width: 595, height: 842 })),
          warnings: entry.warnings ?? [],
          refusal: entry.refusal ?? null,
        }),
      );
    }
    if (path === "/api/page-edits/{id}") return Promise.resolve(ok(operation()));
    return Promise.resolve(ok({}));
  });
}

function renderAt(url: string) {
  render(
    <MemoryRouter initialEntries={[url]}>
      <Routes>
        <Route path="/documents/pages" element={<PageEditorPage />} />
        <Route path="/documents/:id" element={<p>detail</p>} />
      </Routes>
    </MemoryRouter>,
  );
}

describe("page editor", () => {
  it("splits a document, sends the plan once and links the new documents", async () => {
    let completed = false;
    answer(() => ({
      id: "x",
      state: completed ? "COMPLETED" : "REQUESTED",
      requestedBy: "m-lisa",
      requestedAt: "2026-10-02T08:00:00Z",
      results: completed
        ? [
            { output: 0, documentId: "n1", title: "Stromrechnung, Teil 1", existing: false, trashed: false },
            { output: 1, documentId: "n2", title: "Mahnung", existing: true, trashed: true },
          ]
        : [],
    }));
    fetchMock.mockResolvedValue(new Response(JSON.stringify({ id: "x", state: "REQUESTED", results: [] }), { status: 202 }));
    renderAt("/documents/pages?add=strom");

    expect(await screen.findAllByTestId("page-edit-page")).toHaveLength(3);
    expect(screen.getByTestId("page-edit-warning").textContent).toContain("Digital signiert");
    expect((screen.getByTestId("page-edit-title") as HTMLInputElement).value).toBe("Stromrechnung");

    fireEvent.click(screen.getAllByTestId("page-edit-split")[1]);
    const outputs = screen.getAllByTestId("page-edit-output");
    expect(outputs).toHaveLength(2);
    expect(within(outputs[1]).getAllByTestId("page-edit-page")).toHaveLength(1);
    const titles = screen.getAllByTestId("page-edit-title") as HTMLInputElement[];
    expect(titles.map((input) => input.value)).toEqual(["Stromrechnung, Teil 1", "Stromrechnung, Teil 2"]);
    fireEvent.change(titles[1], { target: { value: "Mahnung" } });
    fireEvent.click(within(outputs[0]).getAllByTestId("page-edit-rotate")[0]);

    fireEvent.click(screen.getByTestId("page-edit-submit"));
    await waitFor(() => expect(fetchMock).toHaveBeenCalledTimes(1));
    const [url, init] = fetchMock.mock.calls[0] as [string, RequestInit];
    expect(url).toBe("/api/page-edits");
    const form = init.body as FormData;
    expect(form.get("scan")).toBeNull();
    const plan = JSON.parse(await (form.get("plan") as Blob).text());
    expect(plan.outputs).toEqual([
      {
        title: "Stromrechnung, Teil 1",
        titleChosen: false,
        categoryId: "f1",
        subjects: [{ kind: "HOUSE", memberId: null }],
        pages: [
          { source: "DOCUMENT", documentId: "strom", pageIndex: 0, rotation: 90 },
          { source: "DOCUMENT", documentId: "strom", pageIndex: 1, rotation: 0 },
        ],
      },
      {
        title: "Mahnung",
        titleChosen: true,
        categoryId: "f1",
        subjects: [{ kind: "HOUSE", memberId: null }],
        pages: [{ source: "DOCUMENT", documentId: "strom", pageIndex: 2, rotation: 0 }],
      },
    ]);

    expect(await screen.findByText("Die neuen Dokumente werden erstellt …")).toBeTruthy();
    completed = true;
    await act(() => new Promise((resolve) => setTimeout(resolve, 1700)));
    const results = await screen.findAllByTestId("page-edit-result-document");
    expect(results.map((entry) => entry.querySelector("a")?.getAttribute("href"))).toEqual(["/documents/n1", "/documents/n2"]);
    expect(results[1].textContent).toContain("Papierkorb");
    // A completed operation leaves no plan behind.
    await waitFor(async () => expect((await loadPlan("m-lisa"))?.outputs ?? []).toEqual([]));
  });

  it("shows why a document cannot be used and keeps the plan after a reload", async () => {
    answer(() => ({}));
    renderAt("/documents/pages?add=geheim,strom");

    expect((await screen.findByTestId("page-edit-refusal")).textContent).toBe(
      "„Geheim“ kann nicht verwendet werden: Die PDF ist mit einem Passwort geschützt.",
    );
    expect(await screen.findAllByTestId("page-edit-page")).toHaveLength(3);
    fireEvent.click(screen.getAllByTestId("page-edit-remove")[0]);
    await waitFor(async () => expect((await loadPlan("m-lisa"))?.outputs[0].pages).toHaveLength(2));

    cleanup();
    renderAt("/documents/pages");
    expect(await screen.findAllByTestId("page-edit-page")).toHaveLength(2);
    expect(screen.getByRole("status").textContent).toContain("angefangener Seitenplan mit 2 Seiten");
  });
});
