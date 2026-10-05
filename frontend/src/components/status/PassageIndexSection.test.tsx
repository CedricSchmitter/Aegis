// @vitest-environment jsdom
import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, expect, it, vi } from "vitest";
import type { PassageCoverage } from "../../api/types";
import i18n from "../../i18n";
import { PassageIndexSection } from "./PassageIndexSection";
const api = vi.hoisted(() => ({ GET: vi.fn(), POST: vi.fn() }));
vi.mock("../../api/client", async (original) => ({ ...(await original<typeof import("../../api/client")>()), api }));
const coverage: PassageCoverage = { total: 10, current: 4, pending: 2, stale: 1, failed: 1, noText: 1,
  sourceUnavailable: 1, oldestVerifiedAt: "2026-10-04T12:00:00Z", generatedAt: "2026-10-05T12:00:00Z",
  freshnessSeconds: 86400, complete: false };
const response = (data: unknown) => ({ data, response: new Response(null, { status: 200 }) });
beforeEach(() => { vi.resetAllMocks(); api.GET.mockResolvedValue(response(coverage)); api.POST.mockResolvedValue(response(undefined)); });
afterEach(cleanup);
it.each(["de", "en"])("shows honest incomplete coverage and source states in %s", async (language) => {
  await i18n.changeLanguage(language); render(<PassageIndexSection />);
  expect((await screen.findByTestId("passage-progress")).textContent).toBe(language === "de"
    ? "4 von 10 Dokumenttexten sind aktuell durchsuchbar." : "4 of 10 document texts are currently searchable.");
  const text = screen.getByTestId("passage-index").textContent;
  expect(text).toContain(language === "de" ? "Quelle nicht erreichbar" : "Source unavailable");
  expect(text).toContain(language === "de" ? "Ein leerer Treffer beweist" : "An empty result therefore cannot prove");
  expect(text).not.toMatch(/passages\.(title|state|incomplete|progress)/);
});
it("saves a rebuild request and refreshes durable progress", async () => {
  await i18n.changeLanguage("de"); render(<PassageIndexSection />); await screen.findByTestId("passage-progress");
  api.GET.mockResolvedValue(response({ ...coverage, current: 0, pending: 10 }));
  fireEvent.click(screen.getByRole("button", { name: "Dokumenttexte neu vorbereiten" }));
  await waitFor(() => expect(api.POST).toHaveBeenCalledWith("/api/documents/passages/rebuild"));
  expect(await screen.findByText("Die erneute Vorbereitung wurde gespeichert und läuft in kleinen Schritten weiter.")).toBeTruthy();
  await waitFor(() => expect(screen.getByTestId("passage-progress").textContent).toContain("0 von 10"));
});
it("keeps source failure visible while providing a retry action", async () => {
  await i18n.changeLanguage("en"); api.GET.mockRejectedValue(new TypeError("unavailable"));
  render(<PassageIndexSection />);
  expect(await screen.findByRole("alert")).toBeTruthy();
  api.GET.mockResolvedValue(response(coverage)); fireEvent.click(screen.getByRole("button", { name: "Refresh status" }));
  expect(await screen.findByTestId("passage-progress")).toBeTruthy();
});
