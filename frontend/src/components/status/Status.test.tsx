// @vitest-environment jsdom
import { cleanup, render, screen, within } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it } from "vitest";
import type { HealthCheck } from "../../api/types";
import i18n from "../../i18n";
import { StatusOverview } from "./StatusOverview";

beforeEach(async () => {
  await i18n.changeLanguage("de");
});
afterEach(cleanup);

const check = (overrides: Partial<HealthCheck>): HealthCheck => ({
  area: "database",
  state: "OK",
  reason: "database.connected",
  params: {},
  lastSuccessAt: null,
  ...overrides,
});

describe("status overview", () => {
  it("answers first that everything is fine and keeps an unchecked area neutral", () => {
    render(<StatusOverview checks={[check({}), check({ area: "ai", state: "UNCHECKED", reason: "ai.unchecked" })]} />);

    expect(screen.getByRole("heading", { level: 2 }).textContent).toBe("Ist alles in Ordnung?");
    expect(screen.getByTestId("overview-summary").textContent).toBe("Alles in Ordnung.");
    const ai = screen.getByTestId("check-ai");
    expect(ai.getAttribute("data-tone")).toBeNull();
    expect(within(ai).getByText("Noch nicht geprüft").getAttribute("data-tone")).toBe("neutral");
    expect(screen.queryByTestId("what-now")).toBeNull();
  });

  it("shows a broken backup in red with its reason and what to do now", () => {
    render(
      <StatusOverview
        checks={[
          check({}),
          check({ area: "backup", state: "PROBLEM", reason: "backup.failed", params: { failureCode: "destination.notMounted" } }),
        ]}
      />,
    );

    expect(screen.getByTestId("overview-summary").textContent).toBe("Ein Bereich braucht Aufmerksamkeit.");
    const backup = screen.getByTestId("check-backup");
    expect(backup.getAttribute("data-tone")).toBe("danger");
    expect(backup.textContent).toContain("Die Backup-Platte ist nicht eingebunden.");
    expect(within(backup).getByText("Problem").getAttribute("data-state")).toBe("CHECK_PROBLEM");
    const whatNow = screen.getByTestId("what-now");
    expect(within(whatNow).getByRole("heading", { level: 3 }).textContent).toBe("Was jetzt?");
    expect(whatNow.textContent).toContain("Ist die USB-Platte am Server angeschlossen");
    expect(whatNow.textContent).toContain("Deine Dokumente in Aegis sind davon nicht betroffen.");
  });

  it("names each area in the what-now card when several are broken", () => {
    render(
      <StatusOverview
        checks={[
          check({ area: "archive", state: "PROBLEM", reason: "archive.unavailable" }),
          check({ area: "storage", state: "PROBLEM", reason: "storage.low", params: { disks: "data" } }),
        ]}
      />,
    );

    const whatNow = screen.getByTestId("what-now");
    expect(whatNow.textContent).toContain("Archiv (Paperless):");
    expect(whatNow.textContent).toContain("Speicherplatz:");
  });

  it("drops the test ids and the what-now card in the compact panel", () => {
    render(<StatusOverview compact checks={[check({ area: "backup", state: "PROBLEM", reason: "backup.stale" })]} />);

    expect(screen.queryByTestId("status-overview")).toBeNull();
    expect(screen.queryByTestId("check-backup")).toBeNull();
    expect(screen.queryByTestId("what-now")).toBeNull();
  });
});
