import { createHash } from "node:crypto";
import { readFileSync } from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { expect, test, type Page } from "@playwright/test";

const here = path.dirname(fileURLToPath(import.meta.url));
const FIXTURE = path.resolve(here, "../../fixtures/synthetic-letter-photo.jpg");
const SKEWED = path.resolve(here, "../../fixtures/synthetic-letter-photo-skewed.jpg");
const runId = Date.now().toString(36);

const userA = { username: process.env.AEGIS_USER_A ?? "anna", password: process.env.AEGIS_PASSWORD_A ?? "" };

async function login(page: Page, user: { username: string; password: string }) {
  await page.goto("/login");
  await page.getByLabel(/Benutzername|Username/).fill(user.username);
  await page.getByLabel(/Passwort|Password/).fill(user.password);
  await page.getByRole("button", { name: /^(Anmelden|Sign in)$/ }).click();
  await expect(page).not.toHaveURL(/\/login$/);
}

async function ensureGerman(page: Page) {
  if ((await page.getByTestId("language-switch").textContent())?.trim() === "DE") {
    const saved = page.waitForResponse((response) => response.url().endsWith("/api/members/me/locale") && response.ok());
    await page.getByTestId("language-switch").click();
    await saved;
  }
}

/** Opens the upload panel unless it is open already: it starts open while the collection is empty (issue #83). */
async function openUpload(page: Page) {
  // The panel's first state follows the folder catalog, so decide only once that has loaded.
  await expect(page.getByTestId("folder-all")).toBeAttached();
  const panel = page.getByTestId("upload-panel");
  if (!(await panel.evaluate((element) => (element as HTMLDetailsElement).open))) {
    await page.getByTestId("upload-panel-toggle").click();
  }
  await expect(panel).toHaveAttribute("open", "");
}

test.describe("photos of paper documents uploaded unchanged", () => {
  test.skip(!userA.password, "AEGIS_PASSWORD_A must be set");

  test("a photographed letter is saved, archived with text recognition, found by its text and trashed @mobile", async ({ page }, info) => {
    test.setTimeout(480_000);
    // Decoders ignore bytes after the JPEG end marker; they make this run's photo new for duplicate detection.
    const title = `Foto e2e ${runId} ${info.project.name}`;
    const photo = Buffer.concat([readFileSync(FIXTURE), Buffer.from(title, "latin1")]);
    const sha256 = createHash("sha256").update(photo).digest("hex");
    await login(page, userA);
    await ensureGerman(page);

    await page.goto("/documents");
    await openUpload(page);
    await expect(page.getByTestId("upload-take-photo")).toBeVisible();
    // "Datei unverändert hochladen" keeps a photo unchanged; the camera and the gallery make scanned pages
    // (scan-capture.spec).
    await page.getByTestId("upload-input").setInputFiles({ name: `${title}.jpg`, mimeType: "image/jpeg", buffer: photo });
    await expect(page.getByTestId("upload-preview")).toBeVisible();
    await page.getByTestId("upload-submit").click();
    await expect(page.getByRole("status").first()).toContainText("dauerhaft gespeichert");
    const card = page.locator("[data-testid=document-card]", { hasText: title }).first();
    await card.getByRole("link").first().click();
    await expect(page.getByTestId("photo-hint")).toBeVisible();
    await expect(page.getByTestId("processing-state")).toContainText("Archiviert", { timeout: 360_000 });
    const documentId = page.url().split("/").pop()!;
    await expect(page.getByTestId("checksum")).toContainText(sha256);
    // The archive read the letter, so there is no hint once the text check arrived.
    await expect(page.getByTestId("text-pending")).toHaveCount(0, { timeout: 60_000 });
    await expect(page.getByTestId("text-hint")).toHaveCount(0);

    // The photo stays the original with its own type; the archive's searchable PDF is offered next to it.
    const original = await page.request.get(`/api/documents/${documentId}/original`, { headers: { Accept: "*/*" } });
    expect(original.ok()).toBeTruthy();
    expect(original.headers()["content-type"]).toBe("image/jpeg");
    expect(original.headers()["content-disposition"]).toContain(".jpg");
    expect(createHash("sha256").update(await original.body()).digest("hex")).toBe(sha256);
    await expect(page.getByTestId("open-archive-pdf")).toBeVisible();
    const searchable = await page.request.get(`/api/documents/${documentId}/archive-pdf`, { headers: { Accept: "*/*" } });
    expect(searchable.ok()).toBeTruthy();
    expect(searchable.headers()["content-type"]).toBe("application/pdf");
    expect((await searchable.body()).subarray(0, 5).toString("latin1")).toBe("%PDF-");

    // Only the recognized text of the photo contains this word; the title does not.
    await page.goto("/documents");
    await page.getByTestId("search-input").fill("Ablesekarte");
    await page.getByRole("button", { name: "Suchen" }).click();
    await expect(page.locator(`a[href="/documents/${documentId}"]`).first()).toBeVisible();

    // Leave no test document in the collection.
    await page.goto(`/documents/${documentId}`);
    await page.getByTestId("trash-document").click();
    await page.getByTestId("trash-document-confirm").click();
    await expect(page.getByTestId("trash-banner")).toBeVisible();
  });

  test("a photo held at an angle that the archive cannot read is marked and explained @mobile", async ({ page }, info) => {
    test.setTimeout(480_000);
    const title = `Schief e2e ${runId} ${info.project.name}`;
    const photo = Buffer.concat([readFileSync(SKEWED), Buffer.from(title, "latin1")]);
    await login(page, userA);
    await ensureGerman(page);

    await page.goto("/documents");
    await openUpload(page);
    await page.getByTestId("upload-input").setInputFiles({ name: `${title}.jpg`, mimeType: "image/jpeg", buffer: photo });
    await page.getByTestId("upload-submit").click();
    await expect(page.getByRole("status").first()).toContainText("dauerhaft gespeichert");
    const card = page.locator("[data-testid=document-card]", { hasText: title }).first();
    await card.getByRole("link").first().click();
    await expect(page.getByTestId("processing-state")).toContainText("Archiviert", { timeout: 360_000 });
    const documentId = page.url().split("/").pop()!;

    // Without AI the page tells why full-text search misses the document and how to capture it better.
    const hint = page.getByTestId("text-hint");
    await expect(hint).toBeVisible({ timeout: 60_000 });
    await expect(hint).toContainText("Kein Text erkannt");
    await expect(hint).toContainText("gerade von oben");
    const box = await hint.boundingBox();
    expect(box && box.width <= (page.viewportSize()?.width ?? 0)).toBeTruthy();

    // The list marks it next to the archival state.
    await page.goto("/documents");
    const listed = page.locator("[data-testid=document-card]", { hasText: title }).first();
    await expect(listed.locator("[data-state=TEXT_NONE]")).toHaveText("Kein Text erkannt");

    // "Neues Foto hochladen" opens the upload panel with the camera button.
    await page.goto(`/documents/${documentId}`);
    await page.getByTestId("text-hint-upload").click();
    await expect(page.getByTestId("upload-take-photo")).toBeVisible();

    // Leave no test document in the collection.
    await page.goto(`/documents/${documentId}`);
    await page.getByTestId("trash-document").click();
    await page.getByTestId("trash-document-confirm").click();
    await expect(page.getByTestId("trash-banner")).toBeVisible();
  });
});
