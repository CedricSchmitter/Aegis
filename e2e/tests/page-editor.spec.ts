import { readFileSync } from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { expect, test, type Page } from "@playwright/test";
import { openUpload } from "./documents";

const here = path.dirname(fileURLToPath(import.meta.url));
const PHOTO = path.resolve(here, "../../fixtures/synthetic-letter-photo.jpg");
const runId = Date.now().toString(36);

const userA = { username: process.env.AEGIS_USER_A ?? "anna", password: process.env.AEGIS_PASSWORD_A ?? "" };

async function login(page: Page, user: { username: string; password: string }) {
  await page.goto("/login");
  await page.getByLabel(/Benutzername|Username/).fill(user.username);
  await page.getByLabel(/Passwort|Password/).fill(user.password);
  await page.getByRole("button", { name: /^(Anmelden|Sign in)$/ }).click();
  await expect(page).not.toHaveURL(/\/login$/);
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

/** A small PDF with one line of text per page; the run id makes its bytes, and so the document, new. */
function textPdf(lines: string[]): Buffer {
  const objects: string[] = [];
  const pageIds = lines.map((_, index) => 4 + index * 2);
  objects[1] = "<< /Type /Catalog /Pages 2 0 R >>";
  objects[2] = `<< /Type /Pages /Kids [${pageIds.map((id) => `${id} 0 R`).join(" ")}] /Count ${lines.length} >>`;
  objects[3] = "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>";
  lines.forEach((line, index) => {
    const content = `BT /F1 24 Tf 72 720 Td (${line}) Tj ET`;
    objects[pageIds[index]] =
      `<< /Type /Page /Parent 2 0 R /MediaBox [0 0 595 842] /Resources << /Font << /F1 3 0 R >> >> ` +
      `/Contents ${pageIds[index] + 1} 0 R >>`;
    objects[pageIds[index] + 1] = `<< /Length ${content.length} >>\nstream\n${content}\nendstream`;
  });
  let pdf = "%PDF-1.4\n";
  const offsets: number[] = [];
  for (let id = 1; id < objects.length; id++) {
    offsets[id] = pdf.length;
    pdf += `${id} 0 obj\n${objects[id]}\nendobj\n`;
  }
  const xref = pdf.length;
  pdf += `xref\n0 ${objects.length}\n0000000000 65535 f \n`;
  for (let id = 1; id < objects.length; id++) pdf += `${String(offsets[id]).padStart(10, "0")} 00000 n \n`;
  pdf += `trailer\n<< /Size ${objects.length} /Root 1 0 R >>\nstartxref\n${xref}\n%%EOF\n`;
  return Buffer.from(pdf, "latin1");
}

/** The fixture photo with a mark of this run on the sheet, so the new page and the result documents are new. */
async function markedPhoto(page: Page, mark: string): Promise<Buffer> {
  const base64 = await page.evaluate(
    async ({ data, text }) => {
      const bitmap = await createImageBitmap(await (await fetch(`data:image/jpeg;base64,${data}`)).blob());
      const canvas = new OffscreenCanvas(bitmap.width, bitmap.height);
      const context = canvas.getContext("2d")!;
      context.drawImage(bitmap, 0, 0);
      context.fillStyle = "#555555";
      context.font = "16px sans-serif";
      context.fillText(text, bitmap.width * 0.4, bitmap.height * 0.78);
      const bytes = new Uint8Array(await (await canvas.convertToBlob({ type: "image/jpeg", quality: 0.9 })).arrayBuffer());
      let binary = "";
      bytes.forEach((byte) => (binary += String.fromCharCode(byte)));
      return btoa(binary);
    },
    { data: readFileSync(PHOTO).toString("base64"), text: mark },
  );
  return Buffer.from(base64, "base64");
}

/** Trashing carries the version the page shows; archived is the last change without AI. */
async function trash(page: Page, documentId: string) {
  await page.goto(`/documents/${documentId}`);
  await expect(page.getByTestId("processing-state")).toContainText("Archiviert", { timeout: 360_000 });
  await page.getByTestId("trash-document").click();
  await expect(page.getByTestId("trash-banner")).toBeVisible();
}

test.describe("page editor", () => {
  test.skip(!userA.password, "AEGIS_PASSWORD_A must be set");

  test("a scan of two letters is split, a photographed page added, and the sources stay @mobile", async ({ page }, info) => {
    // The editor is made for phones; the desktop layout uses the same components.
    test.skip(info.project.name !== "mobile-chromium", "runs at phone width");
    test.setTimeout(900_000);
    await login(page, userA);
    await ensureGerman(page);

    const title = `Seiteneditor e2e ${runId}`;
    await page.goto("/documents");
    await openUpload(page);
    const pdf = textPdf([1, 2, 3].map((number) => `Brief ${number} ${runId}`));
    await page.getByTestId("upload-input").setInputFiles({ name: `${title}.pdf`, mimeType: "application/pdf", buffer: pdf });
    const uploaded = page.waitForResponse(
      (response) => response.request().method() === "POST" && new URL(response.url()).pathname === "/api/documents",
    );
    await page.getByTestId("upload-submit").click();
    const sourceId = ((await (await uploaded).json()) as { document: { id: string } }).document.id;

    // The source document's page section opens the editor with its pages.
    await page.goto(`/documents/${sourceId}`);
    await page.getByTestId("edit-pages").click();
    await expect(page).toHaveURL(/\/documents\/pages$/);
    const pages = page.getByTestId("page-edit-page");
    await expect(pages).toHaveCount(3);
    await expect(page.getByTestId("page-edit-title")).toHaveValue(title);
    // The server renders the previews.
    await expect(pages.first().locator("img")).toHaveJSProperty("complete", true);
    expect(await pages.first().locator("img").evaluate((image: HTMLImageElement) => image.naturalWidth)).toBeGreaterThan(100);

    // Split after the first page: two letters, prefilled as parts of the source.
    await page.getByTestId("page-edit-split").first().click();
    const outputs = page.getByTestId("page-edit-output");
    await expect(outputs).toHaveCount(2);
    await expect(page.getByTestId("page-edit-title").nth(1)).toHaveValue(`${title}, Teil 2`);
    await page.getByTestId("page-edit-title").nth(1).fill(`${title} Mahnung`);

    // A forgotten page from the camera: the scan pipeline's check view, then it joins the last document.
    const photo = await markedPhoto(page, `${runId} ${info.project.name}`);
    await page.getByTestId("page-edit-image-input").setInputFiles({ name: "IMG_1.jpg", mimeType: "image/jpeg", buffer: photo });
    const corners = page.getByTestId("page-editor");
    await expect(corners).toHaveAttribute("data-state", "detected", { timeout: 60_000 });
    await page.getByTestId("page-editor-apply").click();
    await expect(outputs.nth(1).getByTestId("page-edit-page")).toHaveCount(3);
    await expect(outputs.nth(1).getByTestId("page-edit-page").last()).toContainText("Neu fotografiert");

    // Reordering with the accessible buttons, turning a page, and a reload during all this keeps the plan.
    await outputs.nth(1).getByTestId("page-edit-later").first().click();
    await outputs.nth(1).getByTestId("page-edit-rotate").last().click();
    await page.reload();
    await expect(page.getByRole("status").first()).toContainText("angefangener Seitenplan mit 4 Seiten");
    await expect(outputs).toHaveCount(2);
    await expect(outputs.nth(1).getByTestId("page-edit-page").nth(0)).toContainText("Seite 3");
    await expect(outputs.nth(1).getByTestId("page-edit-page").nth(1)).toContainText("Seite 2");
    await expect(outputs.nth(1).getByTestId("page-edit-page").last()).toHaveAttribute("data-rotation", "90");
    await expect(page.getByTestId("page-edit-submit")).toBeEnabled({ timeout: 60_000 });

    const requested = page.waitForResponse(
      (response) => response.request().method() === "POST" && new URL(response.url()).pathname === "/api/page-edits",
    );
    await page.getByTestId("page-edit-submit").click();
    expect((await requested).status()).toBe(202);
    const result = page.getByTestId("page-edit-result");
    await expect(result).toHaveAttribute("data-state", "COMPLETED", { timeout: 180_000 });
    const links = result.getByTestId("page-edit-result-document").getByRole("link");
    await expect(links).toHaveText([`${title}, Teil 1`, `${title} Mahnung`]);
    const resultIds = await links.evaluateAll((anchors) =>
      anchors.map((anchor) => anchor.getAttribute("href")!.split("/").pop()!),
    );

    // The second letter knows where its pages came from, including the photographed one.
    await page.goto(`/documents/${resultIds[1]}`);
    const ranges = page.getByTestId("derivation-range");
    await expect(ranges).toHaveText([
      `Seite 1 · Erstellt aus Seite 3 von ${title}`,
      `Seite 2 · Erstellt aus Seite 2 von ${title}`,
      "Seite 3 · Neu fotografiert",
    ]);
    const resultPages = await page.request.get(`/api/documents/${resultIds[1]}/pages`);
    expect(((await resultPages.json()) as { pages: unknown[] }).pages).toHaveLength(3);

    // The source is unchanged and lists both new documents.
    await page.goto(`/documents/${sourceId}`);
    await expect(page.getByTestId("derived-document")).toHaveCount(2);
    await expect(page.getByTestId("trash-banner")).toHaveCount(0);

    for (const id of [...resultIds, sourceId]) await trash(page, id);
  });
});
