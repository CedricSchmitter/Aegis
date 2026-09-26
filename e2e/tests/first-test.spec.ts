import { createHash } from "node:crypto";
import { readFileSync } from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { expect, test, type Page } from "@playwright/test";

const here = path.dirname(fileURLToPath(import.meta.url));
const FIXTURE = path.resolve(here, "../../fixtures/synthetic-contract.pdf");
const FIXTURE_SHA256 = readFileSync(path.resolve(here, "../../fixtures/synthetic-contract.pdf.sha256"), "utf8").split(/\s+/)[0];
// Each run uploads unique bytes (fixture plus a trailing PDF comment) so duplicate detection of earlier runs does
// not short-circuit the flow; the checksum of exactly these bytes must come back unchanged.
const runId = Date.now().toString(36);
const RUN_PDF = Buffer.concat([readFileSync(FIXTURE), Buffer.from(`\n%% e2e run ${runId}\n`, "latin1")]);
const RUN_SHA256 = createHash("sha256").update(RUN_PDF).digest("hex");

const userA = { username: process.env.AEGIS_USER_A ?? "anna", password: process.env.AEGIS_PASSWORD_A ?? "" };
const userB = { username: process.env.AEGIS_USER_B ?? "ben", password: process.env.AEGIS_PASSWORD_B ?? "" };

async function login(page: Page, user: { username: string; password: string }) {
  await page.goto("/login");
  await page.getByLabel(/Benutzername|Username/).fill(user.username);
  await page.getByLabel(/Passwort|Password/).fill(user.password);
  await page.getByRole("button", { name: /^(Anmelden|Sign in)$/ }).click();
  await expect(page).not.toHaveURL(/\/login$/);
  await page.goto("/today");
  await expect(page).toHaveURL(/\/today$/);
}

async function logout(page: Page) {
  await page.getByRole("button", { name: /Abmelden|Log out/ }).click();
  await expect(page).toHaveURL(/\/login$/);
}

/** The language switch lives in Settings only (issue #83); switch there if needed and return to the page. */
async function ensureGerman(page: Page) {
  const back = page.url();
  await page.goto("/settings");
  await expect(page.getByText(/Angemeldet als|Signed in as/)).toBeVisible();
  if ((await page.getByTestId("language-switch").textContent())?.trim() === "DE") {
    const saved = page.waitForResponse((response) => response.url().endsWith("/api/members/me/locale") && response.ok());
    await page.getByTestId("language-switch").click();
    await saved;
  }
  await page.goto(back);
}

test.describe("first runnable test", () => {
  test.skip(!userA.password || !userB.password, "AEGIS_PASSWORD_A and AEGIS_PASSWORD_B must be set");

  test("unauthenticated API access is rejected and the version endpoint is public", async ({ request }) => {
    const documents = await request.get("/api/documents");
    expect(documents.status()).toBe(401);
    const version = await request.get("/api/system/version");
    expect(version.ok()).toBeTruthy();
    expect((await version.json()).commit).toBeTruthy();
    expect(FIXTURE_SHA256).toHaveLength(64);
  });

  test("wrong password shows a localized error and keeps the login page", async ({ page }) => {
    await page.goto("/login");
    await page.getByLabel(/Benutzername|Username/).fill(userA.username);
    await page.getByLabel(/Passwort|Password/).fill("definitely-wrong-password");
    await page.getByRole("button", { name: /^(Anmelden|Sign in)$/ }).click();
    await expect(page.getByRole("alert")).toContainText(/Benutzername oder Passwort ist falsch|Username or password is wrong/);
    await expect(page).toHaveURL(/\/login$/);
  });

  test("language switch persists per member and does not change stored data", async ({ page }) => {
    await login(page, userA);
    await expect(page.getByRole("heading", { level: 1 })).toContainText(/Heute|Today/);
    // The header has no language switch any more; it sits in Settings only (issue #83, F7).
    await expect(page.getByTestId("language-switch")).toHaveCount(0);
    await page.goto("/settings");
    const toggle = page.getByTestId("language-switch");
    const current = (await toggle.textContent())?.trim();
    const switchLanguage = async () => {
      // Reload only after the member preference is stored; otherwise the reload can race the request.
      const saved = page.waitForResponse((response) => response.url().endsWith("/api/members/me/locale") && response.ok());
      await toggle.click();
      await saved;
    };
    await switchLanguage();
    const heading = current === "EN" ? "Settings" : "Einstellungen";
    await expect(page.getByRole("heading", { level: 1 })).toContainText(heading);
    await page.goto("/today");
    await expect(page.getByRole("heading", { level: 1 })).toContainText(current === "EN" ? "Today" : "Heute");
    await page.goto("/settings");
    await expect(page.getByRole("heading", { level: 1 })).toContainText(heading);
    await switchLanguage();
    await expect(page.getByRole("heading", { level: 1 })).toContainText(current === "EN" ? "Einstellungen" : "Settings");
    await logout(page);
  });

  test("upload, archival, original, linked task, shared visibility, notices and restart-safe records", async ({ page, browser }) => {
    test.setTimeout(600_000);
    await login(page, userA);
    await ensureGerman(page);

    // Upload a synthetic PDF and wait for real archival.
    await page.getByRole("link", { name: "Dokumente" }).first().click();
    await page.getByTestId("upload-panel-toggle").click();
    // Nothing is ticked at first and "both" is no choice: a document about both persons names each (issue #83).
    const subjects = page.getByTestId("upload-subjects");
    await expect(subjects.getByRole("checkbox")).not.toHaveCount(0);
    await expect(subjects.getByRole("checkbox", { checked: true })).toHaveCount(0);
    await expect(subjects).not.toContainText("Beide");
    await subjects.getByRole("checkbox", { name: await memberDisplayName(page, userA.username), exact: true }).check();
    await page.getByTestId("upload-input").setInputFiles({
      name: `Stromvertrag e2e ${runId}.pdf`,
      mimeType: "application/pdf",
      buffer: RUN_PDF,
    });
    await page.getByTestId("upload-submit").click();
    await expect(page.getByRole("status").first()).toContainText("dauerhaft gespeichert");
    const card = page.locator("[data-testid=document-card]", { hasText: `Stromvertrag e2e ${runId}` }).first();
    await expect(card).toBeVisible();
    await card.getByRole("link").first().click();
    await expect(page.getByTestId("processing-state")).toContainText("Archiviert", { timeout: 300_000 });
    const documentUrl = page.url();
    const documentId = documentUrl.split("/").pop()!;
    // Technical data sits in a collapsed section (issue #83, F15).
    await expect(page.getByTestId("checksum")).toBeHidden();
    await page.getByTestId("technical-details").locator("summary").click();
    await expect(page.getByTestId("checksum")).toContainText(RUN_SHA256);

    // Original bytes are unchanged (checked through the authenticated API of this browser session).
    const original = await page.request.get(`/api/documents/${documentId}/original`);
    expect(original.ok()).toBeTruthy();
    expect(original.headers()["x-aegis-original-source"]).toBe("ARCHIVE");
    expect(createHash("sha256").update(await original.body()).digest("hex")).toBe(RUN_SHA256);
    // A PDF with its own text layer has no separate searchable PDF.
    await expect(page.getByTestId("text-pending")).toHaveCount(0, { timeout: 60_000 });
    await expect(page.getByTestId("open-archive-pdf")).toHaveCount(0);
    await expect(page.getByTestId("scan-hint")).toHaveCount(0);

    // Search by content (full text of the archive) and by title.
    await page.getByRole("link", { name: "Dokumente" }).first().click();
    await page.getByTestId("search-input").fill("Kuendigungsfrist");
    await page.getByRole("button", { name: "Suchen" }).click();
    await expect(page.locator("[data-testid=document-card]", { hasText: `Stromvertrag e2e ${runId}` })).toBeVisible();

    // Create a linked task assigned to the other member with a deadline and a planned date.
    await page.goto(documentUrl);
    await page.getByTestId("new-task-toggle").click();
    await page.getByTestId("task-title").fill(`Kündigen ${runId}`);
    await page.getByTestId("task-owner").selectOption({ label: await memberDisplayName(page, userB.username) });
    await page.getByTestId("task-due").fill("2026-12-31");
    await page.getByTestId("task-planned").fill("2026-10-01");
    await page.getByTestId("task-create").click();
    const taskItem = page.locator("[data-testid=task-item]", { hasText: `Kündigen ${runId}` });
    await expect(taskItem).toBeVisible();
    await expect(taskItem).toContainText("Fällig");

    // Reschedule work: the deadline must stay.
    await taskItem.getByTestId("task-plan").click();
    await taskItem.getByTestId("task-date-input").fill("2026-11-15");
    await taskItem.getByRole("button", { name: "Speichern" }).click();
    await expect(taskItem).toContainText("31.12.2026");
    await expect(taskItem).toContainText("15.11.2026");
    await logout(page);

    // The other member sees the document, the notice and completes the task.
    const contextB = await browser.newContext({ locale: "de-DE" });
    const pageB = await contextB.newPage();
    await login(pageB, userB);
    await ensureGerman(pageB);
    // "Prüfen" and "Hinweise" are one inbox (issue #83); the old address still leads to its messages.
    await pageB.goto("/notices");
    await expect(pageB).toHaveURL(/\/inbox#messages$/);
    await pageB.getByRole("link", { name: /^Eingang/ }).first().click();
    await expect(pageB.getByRole("heading", { level: 1 })).toHaveText("Eingang");
    await expect(pageB.getByRole("heading", { level: 2, name: "Zu prüfen" })).toBeVisible();
    const messages = pageB.getByTestId("inbox-messages");
    await expect(messages.getByRole("heading", { level: 2, name: "Mitteilungen" })).toBeVisible();
    // A successful archival no longer adds a message; the upload already confirmed saving.
    await expect(messages.locator("[data-testid=notice]", { hasText: "archiviert" })).toHaveCount(0);
    const notice = messages.locator("[data-testid=notice]", { hasText: `Kündigen ${runId}` });
    await expect(notice).toBeVisible();
    await expect(notice).toHaveAttribute("data-unread", "true");
    await notice.getByRole("link", { name: "Öffnen" }).click();
    await expect(pageB.getByRole("heading", { level: 1 })).toContainText(`Kündigen ${runId}`);
    await pageB.getByTestId("task-complete").click();
    await expect(pageB.locator("[data-testid=task-item]")).toContainText("Erledigt");
    await pageB.goto(documentUrl);
    await expect(pageB.getByTestId("processing-state")).toContainText("Archiviert");
    await pageB.getByRole("link", { name: "Heute" }).first().click();
    await expect(pageB.getByRole("heading", { level: 1 })).toContainText("Heute");
    // Notes that only describe the setup are gone (issue #83, F6): no calendar note and no pilot banner.
    await expect(pageB.getByText("Kein Kalender verbunden")).toHaveCount(0);
    await expect(pageB.getByText("Haushaltstest über HTTPS")).toHaveCount(0);
    await contextB.close();

    // The first member sees the completion and the status page answers "is everything OK?" in words (issue #83).
    await login(page, userA);
    await page.goto(documentUrl);
    await expect(page.locator("[data-testid=task-item]", { hasText: `Kündigen ${runId}` })).toContainText("Erledigt");
    await page.getByRole("link", { name: "Status" }).first().click();
    const overview = page.getByTestId("status-overview");
    await expect(overview.getByRole("heading", { level: 2 })).toHaveText("Ist alles in Ordnung?");
    await expect(page.getByTestId("check-database").locator("[data-state]")).toHaveAttribute("data-state", "CHECK_OK");
    await expect(page.getByTestId("check-archive")).toContainText("Erreichbar");
    // CI has no external backup: the overview names it as a problem instead of hiding it (ADR-0015).
    await expect(page.getByTestId("check-backup")).toContainText("Es ist kein externes Backup eingerichtet.");
    await expect(page.getByTestId("check-backup").locator("[data-state]")).toHaveAttribute("data-state", "CHECK_PROBLEM");
    await expect(page.getByTestId("check-storage").locator("[data-state]")).toHaveAttribute("data-state", /^CHECK_(OK|PROBLEM)$/);
    // Integrations that are not set up are not listed: no calendar, mail, push or Telegram, and no AI without credentials.
    for (const area of ["calendar", "mail", "webPush", "telegram", "export", "ai"]) {
      await expect(page.getByTestId(`check-${area}`)).toHaveCount(0);
    }
    await expect(overview).not.toContainText("Nicht eingerichtet");
    // The owner's details are collapsed until opened.
    const details = page.getByTestId("technical-details");
    await expect(page.getByTestId("backup-card")).toBeHidden();
    await details.getByText("Technische Details").click();
    await expect(page.getByTestId("backup-card")).toContainText(/Nicht eingerichtet/);
    await expect(page.getByTestId("disk-data")).toContainText("Datenspeicher des Servers");
    await expect(page.getByTestId("disk-data").locator("[data-state]")).toHaveAttribute("data-state", /^DISK_(OK|LOW)$/);
    // Recurring background jobs are scheduled and either ran successfully or wait for their first run (ADR-0013).
    for (const job of ["reconcile-review-inbox", "check-archive-text", "reconcile-documents"]) {
      await expect(page.getByTestId(`job-${job}`).locator("[data-state]")).toHaveAttribute("data-state", /^JOB_(OK|PENDING)$/);
    }
    await expect(page.getByTestId("job-reconcile-review-inbox")).toContainText("Prüfkorb abgleichen");
    // Without provider credentials the AI boundary says so and capture, search and tasks above still worked.
    await expect(page.getByTestId("ai-section")).toContainText("Kein KI-Anbieter eingerichtet");
    await expect(page.getByTestId("ai-check")).toBeDisabled();
    await logout(page);
  });

  test("records persist after restart: documents and tasks from earlier runs are still there", async ({ page }) => {
    await login(page, userA);
    const documents = (await (await page.request.get("/api/documents")).json()) as {
      items: { title: string; processing: { state: string } }[];
    };
    const archived = documents.items.filter((d) => d.title.startsWith("Stromvertrag e2e") && d.processing.state === "ARCHIVED");
    expect(archived.length).toBeGreaterThanOrEqual(1);
    const tasks = (await (await page.request.get("/api/tasks?includeDone=true")).json()) as { title: string; status: string }[];
    expect(tasks.some((t) => t.title.startsWith("Kündigen") && t.status === "DONE")).toBeTruthy();
    await page.getByRole("link", { name: /Dokumente|Documents/ }).first().click();
    await expect(page.locator("[data-testid=document-card]").first()).toBeVisible();
    await logout(page);
  });

  test("mobile layout shows the bottom navigation and the upload controls @mobile", async ({ page }) => {
    await login(page, userA);
    await expect(page.getByRole("navigation").last()).toBeVisible();
    await page.getByRole("link", { name: /Dokumente|Documents/ }).last().click();
    await page.getByTestId("upload-panel-toggle").click();
    for (const control of ["upload-take-photo", "upload-choose-image", "upload-choose-file"]) {
      await expect(page.getByTestId(control)).toBeVisible();
    }
    // The camera control opens the rear camera directly on a phone.
    await expect(page.getByTestId("upload-camera-input")).toHaveAttribute("capture", "environment");
    await logout(page);
  });
});

async function memberDisplayName(page: Page, username: string): Promise<string> {
  const members = (await (await page.request.get("/api/members")).json()) as { username: string; displayName: string }[];
  return members.find((m) => m.username === username)?.displayName ?? username;
}
