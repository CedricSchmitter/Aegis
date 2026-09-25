import { readFileSync } from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { expect, test, type Page } from "@playwright/test";

const here = path.dirname(fileURLToPath(import.meta.url));
const STRAIGHT = path.resolve(here, "../../fixtures/synthetic-letter-photo.jpg");
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

/**
 * The fixture photo with a small mark of this run on the empty lower part of the sheet. Scanning decodes the pixels
 * and keeps only the sheet, so appended bytes or a mark on the surface would not make the resulting PDF new; a mark
 * on the paper does, which keeps duplicate detection from returning a document of an earlier run.
 */
async function markedPhoto(page: Page, file: string, mark: string): Promise<Buffer> {
  const base64 = await page.evaluate(
    async ({ data, text }) => {
      const bitmap = await createImageBitmap(await (await fetch(`data:image/jpeg;base64,${data}`)).blob());
      const canvas = new OffscreenCanvas(bitmap.width, bitmap.height);
      const context = canvas.getContext("2d")!;
      context.drawImage(bitmap, 0, 0);
      context.fillStyle = "#555555";
      context.font = "16px sans-serif";
      context.fillText(text, bitmap.width * 0.4, bitmap.height * 0.78);
      const blob = await canvas.convertToBlob({ type: "image/jpeg", quality: 0.9 });
      const bytes = new Uint8Array(await blob.arrayBuffer());
      let binary = "";
      bytes.forEach((byte) => (binary += String.fromCharCode(byte)));
      return btoa(binary);
    },
    { data: readFileSync(file).toString("base64"), text: mark },
  );
  return Buffer.from(base64, "base64");
}

/** Saves the scan and returns the id of the new document from the upload answer. */
async function save(page: Page): Promise<string> {
  const uploaded = page.waitForResponse(
    (response) => response.request().method() === "POST" && new URL(response.url()).pathname === "/api/documents",
  );
  await page.getByTestId("upload-submit").click();
  const answer = (await (await uploaded).json()) as { document: { id: string }; duplicate: boolean };
  expect(answer.duplicate).toBe(false);
  await expect(page.getByRole("status").first()).toContainText("dauerhaft gespeichert");
  return answer.document.id;
}

async function trash(page: Page, documentId: string) {
  await page.goto(`/documents/${documentId}`);
  await page.getByTestId("trash-document").click();
  await page.getByTestId("trash-document-confirm").click();
  await expect(page.getByTestId("trash-banner")).toBeVisible();
}

test.describe("scanned photos", () => {
  test.skip(!userA.password, "AEGIS_PASSWORD_A must be set");

  test("a letter photographed at an angle is straightened into a readable PDF @mobile", async ({ page }, info) => {
    test.setTimeout(480_000);
    await login(page, userA);
    await ensureGerman(page);
    await page.goto("/documents");
    const photo = await markedPhoto(page, SKEWED, `${runId} ${info.project.name}`);

    await page.getByTestId("upload-panel-toggle").click();
    await page.getByTestId("upload-image-input").setInputFiles({ name: "IMG_1.jpg", mimeType: "image/jpeg", buffer: photo });
    const scanned = page.getByTestId("scan-page");
    await expect(scanned).toHaveCount(1);
    // The sheet was found and straightened (OpenCV is loaded on first use, so allow for the download).
    await expect(scanned.first()).toHaveAttribute("data-mode", "scan", { timeout: 60_000 });
    // While scanning, a file cannot be chosen: it would replace the pages.
    await expect(page.getByTestId("upload-choose-file")).toHaveCount(0);
    await expect(page.getByTestId("upload-submit")).toHaveText("1 Seite als Dokument speichern");
    const documentId = await save(page);
    await expect(page.getByTestId("scan-pages")).toHaveCount(0);
    await page.goto(`/documents/${documentId}`);
    const original = await page.request.get(`/api/documents/${documentId}/original`, { headers: { Accept: "*/*" } });
    expect(original.headers()["content-type"]).toBe("application/pdf");
    const pdf = (await original.body()).toString("latin1");
    expect(pdf.startsWith("%PDF-")).toBeTruthy();
    expect(pdf).toContain("/Count 1");

    // The same photo held at 10 degrees is read as no text when uploaded unchanged (photo-capture.spec); the
    // straightened scan is read.
    await expect(page.getByTestId("processing-state")).toContainText("Archiviert", { timeout: 360_000 });
    await expect(page.getByTestId("text-pending")).toHaveCount(0, { timeout: 60_000 });
    await expect(page.getByTestId("text-hint")).toHaveCount(0);

    await trash(page, documentId);
  });

  test("several photos become one document in the chosen order, and a reload keeps them", async ({ page }, info) => {
    test.setTimeout(480_000);
    await login(page, userA);
    await ensureGerman(page);
    await page.goto("/documents");
    const first = await markedPhoto(page, STRAIGHT, `${runId} a ${info.project.name}`);
    const second = await markedPhoto(page, SKEWED, `${runId} b ${info.project.name}`);

    await page.getByTestId("upload-panel-toggle").click();
    await page.getByTestId("upload-image-input").setInputFiles([
      { name: "IMG_1.jpg", mimeType: "image/jpeg", buffer: first },
      { name: "IMG_2.jpg", mimeType: "image/jpeg", buffer: second },
    ]);
    const pages = page.getByTestId("scan-page");
    await expect(pages).toHaveCount(2);
    for (const index of [0, 1]) {
      await expect(pages.nth(index)).toHaveAttribute("data-mode", "scan", { timeout: 60_000 });
    }

    // Android may close the tab while the camera is open; the pages are still there afterwards.
    await page.reload();
    await expect(page.getByTestId("scan-page")).toHaveCount(2);
    await expect(page.getByText("Dein angefangener Scan mit 2 Seiten ist wieder da.")).toBeVisible();

    // The second page first, then turned once: it goes into the PDF as a landscape page.
    await page.getByRole("button", { name: "Seite 2 nach vorn" }).click();
    await page.getByRole("button", { name: "Seite 1 drehen" }).click();
    await expect(pages.first()).toHaveAttribute("data-mode", "scan", { timeout: 60_000 });

    // Corners can be corrected by hand; "Originalfoto verwenden" keeps the whole photo instead.
    await page.getByRole("button", { name: "Ecken von Seite 2 bearbeiten" }).click();
    await expect(page.getByTestId("page-editor")).toBeVisible();
    const box = (await page.getByTestId("page-editor").locator("img").boundingBox())!;
    expect(box.width).toBeLessThanOrEqual(page.viewportSize()!.width);
    await page.getByTestId("page-editor-keep-photo").click();
    await expect(pages.nth(1)).toHaveAttribute("data-mode", "photo", { timeout: 60_000 });

    await expect(page.getByTestId("upload-submit")).toHaveText("2 Seiten als ein Dokument speichern");
    const documentId = await save(page);
    const original = await page.request.get(`/api/documents/${documentId}/original`, { headers: { Accept: "*/*" } });
    const pdf = (await original.body()).toString("latin1");
    expect(pdf).toContain("/Count 2");
    const boxes = [...pdf.matchAll(/\/MediaBox \[0 0 ([\d.]+) ([\d.]+)\]/g)].map((m) => [Number(m[1]), Number(m[2])]);
    expect(boxes).toHaveLength(2);
    // Page 1 is the rotated scan (landscape), page 2 the whole photo (portrait).
    expect(boxes[0][0]).toBeGreaterThan(boxes[0][1]);
    expect(boxes[1][0]).toBeLessThan(boxes[1][1]);

    // Saved scans are gone from the browser: a reload shows no pages.
    await page.goto("/documents");
    await expect(page.getByTestId("scan-page")).toHaveCount(0);

    await trash(page, documentId);
  });
});
