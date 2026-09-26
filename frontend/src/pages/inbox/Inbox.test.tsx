// @vitest-environment jsdom
import { cleanup, render, screen, waitFor, within } from "@testing-library/react";
import { MemoryRouter } from "react-router";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import i18n from "../../i18n";
import { MessagesSection } from "./MessagesSection";
import { ReviewSection } from "./ReviewSection";

const api = vi.hoisted(() => ({ GET: vi.fn(), POST: vi.fn() }));
vi.mock("../../api/client", async (original) => ({ ...(await original<typeof import("../../api/client")>()), api }));

beforeEach(async () => {
  await i18n.changeLanguage("de");
});
afterEach(() => {
  cleanup();
  vi.resetAllMocks();
});

const ok = (data: unknown) => ({ data, response: new Response(null, { status: 200 }) });

const card = (fields: Record<string, unknown>) => ({
  id: "r1",
  category: "PROCESSING_FAILURE",
  reason: "ARCHIVAL_FAILED",
  priority: "HIGH",
  titleKey: "review.archiveFailed",
  params: { title: "Stromrechnung", errorCode: "archive.unreachable" },
  status: "OPEN",
  actions: ["RETRY_ARCHIVAL"],
  linkType: "DOCUMENT",
  linkId: "d1",
  version: 1,
  updatedAt: "2026-09-27T08:00:00Z",
  ...fields,
});

const notice = (fields: Record<string, unknown>) => ({
  id: "n1",
  kind: "TASK_ASSIGNED",
  params: { title: "Zählerstand melden" },
  linkType: "TASK",
  linkId: "t1",
  createdAt: "2026-09-27T08:00:00Z",
  readAt: null,
  ...fields,
});

describe("review cards", () => {
  it("offer one highlighted action with the source and Hide quietly next to it, and show a failure in red", async () => {
    api.GET.mockResolvedValue(ok([card({}), card({ id: "r2", category: "CLASSIFICATION", reason: "UNCERTAIN_CLASSIFICATION", actions: [] })]));
    render(
      <MemoryRouter>
        <ReviewSection onLoaded={vi.fn()} />
      </MemoryRouter>,
    );

    const [failed, classification] = await screen.findAllByTestId("review-card");
    expect(failed.getAttribute("data-tone")).toBe("danger");
    expect(within(failed).getByRole("button", { name: "Archivierung wiederholen" }).className).toContain("btn-secondary");
    expect(within(failed).getByRole("link", { name: "Dokument prüfen" }).className).toContain("btn-ghost");
    expect(within(failed).getByRole("button", { name: "Ausblenden" }).className).toContain("btn-ghost");
    expect(classification.getAttribute("data-tone")).toBeNull();
    expect(within(classification).getByRole("link", { name: "Dokument prüfen" }).className).toContain("btn-secondary");
  });
});

describe("messages", () => {
  it("show a failed backup in red and a new message in the data colour", async () => {
    api.GET.mockResolvedValue(
      ok([
        notice({ id: "n2", kind: "BACKUP_FAILED", params: { state: "FAILED", reason: "destination.notMounted" }, linkType: "STATUS", linkId: "backup" }),
        notice({}),
      ]),
    );
    render(
      <MemoryRouter>
        <MessagesSection />
      </MemoryRouter>,
    );

    await waitFor(() => expect(screen.getAllByTestId("notice")).toHaveLength(2));
    const [backup, assigned] = screen.getAllByTestId("notice");
    expect(backup.getAttribute("data-tone")).toBe("danger");
    expect(within(backup).getByRole("link").getAttribute("href")).toBe("/status");
    expect(assigned.getAttribute("data-tone")).toBe("data");
    expect(within(assigned).getByRole("link").textContent).toContain("Zählerstand melden");
    expect(screen.getByRole("heading", { level: 2 }).textContent).toContain("2 neu");
  });
});
