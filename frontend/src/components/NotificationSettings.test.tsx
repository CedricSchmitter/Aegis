// @vitest-environment jsdom
import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import i18n from "../i18n";
import { NotificationSettings } from "./NotificationSettings";

const api = vi.hoisted(() => ({ GET: vi.fn(), POST: vi.fn(), PUT: vi.fn(), DELETE: vi.fn() }));
vi.mock("../api/client", async (original) => ({ ...(await original<typeof import("../api/client")>()), api }));

const ok = (data: unknown) => ({ data, response: new Response(null, { status: 200 }) });
const preferences = {
  categories: ["TASKS", "DEADLINES", "REVIEW", "SYSTEM"],
  quietStart: "22:00:00",
  quietEnd: "07:00:00",
  showDetails: false,
  version: 0,
};

beforeEach(async () => {
  await i18n.changeLanguage("de");
  api.GET.mockImplementation((path: string) => {
    if (path === "/api/push/config") return Promise.resolve(ok({ available: true, publicKey: "key" }));
    if (path === "/api/push/devices") {
      return Promise.resolve(
        ok([{ id: "d-1", deviceLabel: "Android · Chrome", state: "FAILING", createdAt: "2026-10-01T08:00:00Z", lastSentAt: null }]),
      );
    }
    return Promise.resolve(ok(preferences));
  });
});
afterEach(() => {
  cleanup();
  vi.resetAllMocks();
});

describe("NotificationSettings", () => {
  it("explains that a browser without Web Push cannot be switched on and lists the other devices", async () => {
    render(<NotificationSettings />);

    expect(await screen.findByText("Dieser Browser kann keine Benachrichtigungen empfangen.")).toBeTruthy();
    expect(screen.getByRole("switch", { name: "Auf diesem Gerät" }).hasAttribute("disabled")).toBe(true);
    expect(await screen.findByText("Android · Chrome")).toBeTruthy();
    expect(screen.getByText("Zustellung schlägt fehl")).toBeTruthy();
    expect(screen.getByLabelText("Von")).toHaveProperty("value", "22:00");
  });

  it("saves a preference change with the version it was based on", async () => {
    api.PUT.mockResolvedValue(ok({ ...preferences, categories: ["TASKS", "DEADLINES", "REVIEW"], version: 1 }));
    render(<NotificationSettings />);

    fireEvent.click(await screen.findByRole("switch", { name: "System" }));

    await waitFor(() =>
      expect(api.PUT).toHaveBeenCalledWith("/api/push/preferences", {
        body: { ...preferences, categories: ["TASKS", "DEADLINES", "REVIEW"] },
      }),
    );
    await waitFor(() => expect(screen.getByRole("switch", { name: "System" }).getAttribute("aria-checked")).toBe("false"));
  });

  it("shows a conflict when the preferences changed on another device", async () => {
    api.PUT.mockResolvedValue({
      error: { code: "version.conflict" },
      response: new Response(null, { status: 409 }),
    });
    render(<NotificationSettings />);

    fireEvent.click(await screen.findByRole("switch", { name: "Ruhezeit" }));

    expect(await screen.findByText("Der Datensatz wurde inzwischen geändert.")).toBeTruthy();
  });
});
