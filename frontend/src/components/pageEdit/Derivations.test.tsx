// @vitest-environment jsdom
import { cleanup, render, screen } from "@testing-library/react";
import { MemoryRouter } from "react-router";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import i18n from "../../i18n";
import { Derivations } from "./Derivations";

const api = vi.hoisted(() => ({ GET: vi.fn() }));
vi.mock("../../api/client", async (original) => ({ ...(await original<typeof import("../../api/client")>()), api }));
vi.mock("../../auth/SessionContext", () => ({ useSession: () => ({ memberName: () => "Lisa" }) }));

const ok = (data: unknown) => ({ data, response: new Response(null, { status: 200 }) });

beforeEach(async () => {
  await i18n.changeLanguage("de");
});
afterEach(() => {
  cleanup();
  vi.resetAllMocks();
});

const range = (first: number, last: number, extra: object) => ({
  firstPage: first,
  lastPage: last,
  rotation: 0,
  firstSourcePage: first,
  lastSourcePage: last,
  ...extra,
});

describe("document derivations", () => {
  it("names the source pages, including trashed, deleted and newly photographed ones", async () => {
    api.GET.mockResolvedValue(
      ok({
        origin: {
          pageEditId: "op",
          createdBy: "m-lisa",
          createdAt: "2026-10-02T08:00:00Z",
          ranges: [
            { ...range(1, 3, { sourceKind: "DOCUMENT", sourceDocumentId: "s1", sourceTitle: "Vertrag", sourceState: "TRASHED" }), firstSourcePage: 2, lastSourcePage: 4 },
            range(4, 4, { sourceKind: "DOCUMENT", sourceDocumentId: "s2", sourceTitle: "Anlage", sourceState: "DELETED" }),
            range(5, 6, { sourceKind: "SCAN", sourceDocumentId: null, sourceTitle: null, sourceState: null }),
          ],
        },
        derived: [
          {
            documentId: "n1",
            title: "Teil 1",
            trashed: true,
            createdBy: "m-lisa",
            createdAt: "2026-10-02T08:00:00Z",
            sourcePages: [
              { firstPage: 1, lastPage: 3 },
              { firstPage: 5, lastPage: 5 },
            ],
          },
        ],
      }),
    );
    render(
      <MemoryRouter>
        <Derivations documentId="d1" />
      </MemoryRouter>,
    );

    const ranges = await screen.findAllByTestId("derivation-range");
    expect(ranges.map((entry) => entry.textContent)).toEqual([
      "Seiten 1–3 · Erstellt aus Seiten 2–4 von Vertrag · im Papierkorb",
      "Seite 4 · Erstellt aus Seite 4 von „Anlage“ (gelöscht)",
      "Seiten 5–6 · Neu fotografiert",
    ]);
    expect(ranges[0].querySelector("a")?.getAttribute("href")).toBe("/documents/s1");
    expect(ranges[1].querySelector("a")).toBeNull();
    expect(screen.getByTestId("derived-document").textContent).toBe("Teil 1aus Seiten 1–3, 5 · im Papierkorb");
  });

  it("shows nothing for a document the page editor did not touch", async () => {
    api.GET.mockResolvedValue(ok({ origin: null, derived: [] }));
    const { container } = render(
      <MemoryRouter>
        <Derivations documentId="d1" />
      </MemoryRouter>,
    );
    await vi.waitFor(() => expect(api.GET).toHaveBeenCalled());
    expect(container.textContent).toBe("");
  });
});
