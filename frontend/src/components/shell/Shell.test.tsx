// @vitest-environment jsdom
import { cleanup, fireEvent, render, screen, within } from "@testing-library/react";
import { MemoryRouter } from "react-router";
import { afterEach, describe, expect, it, vi } from "vitest";
import i18n from "../../i18n";
import type { StatusSummary } from "../../lib/shellStatus";
import { AvatarMenu } from "./AvatarMenu";
import { BottomNav } from "./Navigation";

const shell = vi.hoisted(() => ({
  status: { tone: "ok", problemAreas: [], lastBackupAt: null } as StatusSummary,
  pending: 0,
}));

vi.mock("./ShellData", () => ({
  useShellData: () => ({ status: shell.status, pending: shell.pending, catalog: null, version: "", desktop: false }),
}));

vi.mock("../../auth/SessionContext", () => ({
  useSession: () => ({ member: { displayName: "Lisa", username: "lisa" }, logout: vi.fn() }),
}));

afterEach(() => {
  cleanup();
  shell.status = { tone: "ok", problemAreas: [], lastBackupAt: null };
  shell.pending = 0;
});

const at = (path: string, ui: React.ReactNode) => render(<MemoryRouter initialEntries={[path]}>{ui}</MemoryRouter>);

describe("bottom navigation", () => {
  it("has the five areas in order, marks the current one and leaves Status out", async () => {
    await i18n.changeLanguage("de");
    at("/tasks/123", <BottomNav />);
    const links = within(screen.getByRole("navigation", { name: "Hauptnavigation" })).getAllByRole("link");
    expect(links.map((link) => link.textContent)).toEqual(["Heute", "Dokumente", "Aufgaben", "Verträge", "Eingang"]);
    expect(links.filter((link) => link.getAttribute("aria-current") === "page").map((link) => link.textContent)).toEqual([
      "Aufgaben",
    ]);
  });

  it("counts open inbox entries with a readable label", async () => {
    await i18n.changeLanguage("de");
    shell.pending = 3;
    at("/today", <BottomNav />);
    expect(screen.getByTestId("inbox-badge").getAttribute("aria-label")).toBe("3 offene Einträge im Eingang");
  });
});

describe("avatar menu", () => {
  it("opens Settings, Status and Trash and closes on Escape with the focus back on the avatar", async () => {
    await i18n.changeLanguage("de");
    at("/today", <AvatarMenu />);
    const toggle = screen.getByRole("button", { name: "Lisa: Einstellungen, Status und Papierkorb" });
    expect(toggle.getAttribute("aria-expanded")).toBe("false");
    expect(screen.queryByTestId("avatar-menu")).toBeNull();

    fireEvent.click(toggle);
    expect(toggle.getAttribute("aria-expanded")).toBe("true");
    const menu = screen.getByTestId("avatar-menu");
    expect(within(menu).getAllByRole("link").map((link) => link.getAttribute("href"))).toEqual(["/settings", "/status", "/trash"]);
    expect(within(menu).getByRole("button", { name: "Abmelden" })).toBeTruthy();

    fireEvent.keyDown(document, { key: "Escape" });
    expect(toggle.getAttribute("aria-expanded")).toBe("false");
    expect(document.activeElement).toBe(toggle);
  });

  it("marks a broken status on the avatar in red and in its name", async () => {
    await i18n.changeLanguage("en");
    shell.status = { tone: "problem", problemAreas: ["backup"], lastBackupAt: null };
    at("/today", <AvatarMenu />);
    expect(screen.getByTestId("avatar-problem")).toBeTruthy();
    expect(screen.getByRole("button", { name: "Lisa: settings, status and trash. The status reports a problem" })).toBeTruthy();
    fireEvent.click(screen.getByTestId("avatar-menu-toggle"));
    expect(screen.getByTestId("avatar-menu-status").textContent).toContain("Problem: Backup");
    await i18n.changeLanguage("de");
  });

  it("closes on a click outside", async () => {
    await i18n.changeLanguage("de");
    at("/today", <AvatarMenu />);
    fireEvent.click(screen.getByTestId("avatar-menu-toggle"));
    fireEvent.pointerDown(document.body);
    expect(screen.getByTestId("avatar-menu-toggle").getAttribute("aria-expanded")).toBe("false");
  });
});
