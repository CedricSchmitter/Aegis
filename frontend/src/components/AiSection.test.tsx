// @vitest-environment jsdom
import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import type { AiOverview } from "../api/types";
import i18n from "../i18n";
import { AiSection } from "./AiSection";

const api = vi.hoisted(() => ({ GET: vi.fn(), POST: vi.fn(), PUT: vi.fn() }));
vi.mock("../api/client", async (original) => ({ ...(await original<typeof import("../api/client")>()), api }));

vi.mock("../auth/SessionContext", () => ({
  useSession: () => ({
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

const ok = (data: unknown) => ({ data, response: new Response(null, { status: 200 }) });

const workload = (name: string, fields: Partial<AiOverview["workloads"][number]> = {}) => ({
  workload: name,
  provider: "anthropic",
  model: "claude-sonnet-5-5",
  family: "sonnet" as const,
  choiceVersion: 0,
  choiceChangedBy: null,
  choiceChangedAt: null,
  providerConfigured: true,
  handlerAvailable: true,
  available: true,
  maxCost: 0.05,
  maxDurationSeconds: 120,
  maxModelCalls: 4,
  maxToolCalls: 6,
  maxOutputTokens: 2048,
  maxAttempts: 3,
  maxConcurrent: 2,
  ...fields,
});

const overview = (classification: Partial<AiOverview["workloads"][number]> = {}): AiOverview => ({
  configured: true,
  memberAllowed: true,
  limitProvider: "anthropic",
  dailyCostLimit: 0.25,
  spentToday: 0,
  currency: "USD",
  maxConcurrentRuns: 1,
  workloads: [
    workload("CONNECTION_CHECK", { provider: null, model: null, family: null }),
    workload("DOCUMENT_CLASSIFICATION", classification),
  ],
  models: [
    { family: "opus", model: "claude-opus-5-5", inputPerMillion: 4, outputPerMillion: 20 },
    { family: "sonnet", model: "claude-sonnet-5-5", inputPerMillion: 2, outputPerMillion: 10 },
    { family: "haiku", model: "claude-haiku-4-5", inputPerMillion: 1, outputPerMillion: 5 },
  ],
});

describe("AI model choice", () => {
  it("offers the families only for workloads with their own model and shows the API model behind the choice", async () => {
    api.GET.mockImplementation((path: string) => Promise.resolve(ok(path === "/api/ai/runs" ? [] : overview())));

    render(<AiSection />);

    const select = (await screen.findByLabelText("Modell")) as HTMLSelectElement;
    expect(select.value).toBe("sonnet");
    expect(Array.from(select.options).map((option) => option.textContent)).toEqual([
      "Opus – am gründlichsten",
      "Sonnet – ausgewogen",
      "Haiku – am schnellsten und günstigsten",
    ]);
    expect(screen.queryByTestId("ai-model-CONNECTION_CHECK")).toBeNull();
    expect(screen.getByText(/Claude API: claude-sonnet-5-5/)).toBeTruthy();
    expect(screen.getByText(/gilt für den ganzen Haushalt/)).toBeTruthy();
  });

  it("sends the chosen family with the version it was based on and shows who changed it", async () => {
    let current = overview();
    api.GET.mockImplementation((path: string) => Promise.resolve(ok(path === "/api/ai/runs" ? [] : current)));
    api.PUT.mockImplementation(() => {
      current = overview({
        family: "opus",
        model: "claude-opus-5-5",
        choiceVersion: 1,
        choiceChangedBy: "m-cedric",
        choiceChangedAt: "2026-09-29T10:00:00Z",
      });
      return Promise.resolve(ok(current));
    });

    render(<AiSection />);
    fireEvent.change(await screen.findByTestId("ai-model-DOCUMENT_CLASSIFICATION"), { target: { value: "opus" } });

    await waitFor(() => expect(screen.getByText(/Geändert von Cedric/)).toBeTruthy());
    expect(api.PUT).toHaveBeenCalledWith("/api/ai/workloads/{workload}/model", {
      params: { path: { workload: "DOCUMENT_CLASSIFICATION" } },
      body: { family: "opus", expectedVersion: 0 },
    });
    expect(screen.getByText("Dokumente einordnen: ab dem nächsten Lauf Opus – am gründlichsten.")).toBeTruthy();
    expect((screen.getByTestId("ai-model-DOCUMENT_CLASSIFICATION") as HTMLSelectElement).value).toBe("opus");
  });

  it("explains a concurrent change by the other member", async () => {
    api.GET.mockImplementation((path: string) => Promise.resolve(ok(path === "/api/ai/runs" ? [] : overview())));
    api.PUT.mockResolvedValue({
      error: { code: "version.conflict" },
      response: new Response(null, { status: 409 }),
    });

    render(<AiSection />);
    fireEvent.change(await screen.findByTestId("ai-model-DOCUMENT_CLASSIFICATION"), { target: { value: "haiku" } });

    await waitFor(() => expect(screen.getByText("Der Datensatz wurde inzwischen geändert.")).toBeTruthy());
  });
});
