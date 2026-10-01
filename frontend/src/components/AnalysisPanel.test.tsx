// @vitest-environment jsdom
import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import type { DocumentDto } from "../api/types";
import i18n from "../i18n";
import { AnalysisPanel } from "./AnalysisPanel";

const api = vi.hoisted(() => ({ GET: vi.fn(), POST: vi.fn(), PUT: vi.fn(), DELETE: vi.fn() }));
vi.mock("../api/client", async (original) => ({ ...(await original<typeof import("../api/client")>()), api }));

vi.mock("../auth/SessionContext", () => ({
  useSession: () => ({ memberName: (id: string) => (id === "m-lisa" ? "Lisa" : null) }),
}));

afterEach(() => {
  cleanup();
  vi.resetAllMocks();
});

const document = {
  id: "d1",
  title: "Scan 0001",
  contentType: "application/pdf",
  version: 2,
  filename: { filename: "Scan-0001_d1.pdf", source: "FALLBACK" },
  processing: { state: "ARCHIVED" },
  analysis: {
    state: "NOT_CLASSIFIED",
    trigger: "UPLOAD",
    requestedBy: "m-lisa",
    candidates: [],
    flags: [],
    generation: 1,
    history: [],
    subjects: [],
    documentDateStatus: "UNKNOWN",
    version: 2,
  },
} as unknown as DocumentDto;

function renderPanel(onError = vi.fn()) {
  render(<AnalysisPanel document={document} onChange={vi.fn()} onError={onError} onMessage={vi.fn()} reload={vi.fn()} />);
  return onError;
}

describe("analysis panel for an upload by a member without AI", () => {
  it.each([
    ["de", "weil die KI für Lisa nicht freigegeben ist", "Nicht eingeordnet", "Jetzt einordnen"],
    ["en", "because AI is not enabled for Lisa", "Not classified", "Classify now"],
  ])("explains the state neutrally and offers to classify it (%s)", async (language, explanation, badge, action) => {
    await i18n.changeLanguage(language);
    renderPanel();

    expect(screen.getByTestId("analysis-state").textContent).toContain(explanation);
    expect(screen.getByText(badge)).toBeTruthy();
    expect(screen.queryByTestId("analysis-failure")).toBeNull();
    expect(screen.getByTestId("request-analysis").textContent).toBe(action);
  });

  it("tells a member without AI access why their own request is refused", async () => {
    await i18n.changeLanguage("de");
    api.POST.mockResolvedValue({
      error: { status: 400, code: "ai.provider.memberNotAllowed" },
      response: new Response(null, { status: 400 }),
    });
    const onError = renderPanel();

    fireEvent.click(screen.getByTestId("request-analysis"));

    await waitFor(() => expect(onError).toHaveBeenCalledWith(expect.stringContaining("nicht freigegeben")));
    expect(api.POST).toHaveBeenCalledWith("/api/documents/{id}/analysis", {
      params: { path: { id: "d1" } },
      body: { expectedVersion: 2 },
    });
  });
});
