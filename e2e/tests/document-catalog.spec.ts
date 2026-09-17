import { readFileSync } from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { expect, test, type Page } from "@playwright/test";

const here = path.dirname(fileURLToPath(import.meta.url));
const FIXTURE = path.resolve(here, "../../fixtures/synthetic-contract.pdf");
// Unique bytes per run so duplicate detection of earlier runs does not short-circuit the upload.
const runId = Date.now().toString(36);
const RUN_PDF = Buffer.concat([readFileSync(FIXTURE), Buffer.from(`\n%% catalog e2e ${runId}\n`, "latin1")]);
const ROOT = `E2E Haus ${runId}`;
const ORGANIZATION = `Telekom e2e ${runId}`;

const userA = { username: process.env.AEGIS_USER_A ?? "anna", password: process.env.AEGIS_PASSWORD_A ?? "" };
const userB = { username: process.env.AEGIS_USER_B ?? "ben", password: process.env.AEGIS_PASSWORD_B ?? "" };

async function login(page: Page, user: { username: string; password: string }) {
  await page.goto("/login");
  await page.getByLabel(/Benutzername|Username/).fill(user.username);
  await page.getByLabel(/Passwort|Password/).fill(user.password);
  await page.getByRole("button", { name: /^(Anmelden|Sign in)$/ }).click();
  await expect(page).not.toHaveURL(/\/login$/);
}

async function ensureGerman(page: Page) {
  if ((await page.getByTestId("language-switch").textContent())?.trim() === "DE") {
    await page.getByTestId("language-switch").click();
    await expect(page.getByTestId("language-switch")).toHaveText("EN");
  }
}

async function createFolder(page: Page, name: string, parentId: string | null): Promise<string> {
  await page.goto("/documents/folders");
  await page.getByTestId("new-folder-name").fill(name);
  await page.locator("#new-folder-parent").selectOption(parentId ?? "");
  await page.getByTestId("create-folder").click();
  const row = page.locator("[data-testid=folder-row]", { hasText: name }).first();
  await expect(row).toBeVisible();
  return (await row.getAttribute("data-folder-id"))!;
}

/** Adds an implied label (by its visible name) and optionally an implied subject to a folder. */
async function mapFolder(page: Page, folderId: string, facet: string, labelName?: string, subject?: string) {
  await page.goto("/documents/folders");
  await page.getByTestId(`edit-folder-${folderId}`).click();
  const row = page.locator(`[data-folder-id="${folderId}"]`);
  if (labelName) {
    await row.getByTestId(`label-picker-${facet}`).fill(labelName);
    await page.getByRole("option", { name: new RegExp(labelName) }).first().click();
  }
  if (subject) {
    await row.getByTestId(`implied-subject-${subject}`).check();
  }
  await row.getByTestId("save-implications").click();
  await expect(page.getByRole("status").first()).toContainText(/Ordnerlabels gespeichert|Folder labels saved/);
}

function chip(page: Page, name: string) {
  return page.locator("[data-testid=label-chip]", { hasText: name }).first();
}

test.describe("document tree, labels and faceted search", () => {
  test.skip(!userA.password || !userB.password, "AEGIS_PASSWORD_A and AEGIS_PASSWORD_B must be set");

  test("folders carry labels, documents inherit them and facets find them", async ({ page, browser }) => {
    test.setTimeout(600_000);
    await login(page, userA);
    await ensureGerman(page);

    // A small household structure with explicit folder labels: House -> Invoices -> Internet.
    const houseId = await createFolder(page, ROOT, null);
    const invoicesId = await createFolder(page, "Rechnungen", houseId);
    const internetId = await createFolder(page, "Internet", invoicesId);
    await mapFolder(page, houseId, "DOCUMENT_TYPE", undefined, "HOUSE");
    await mapFolder(page, invoicesId, "DOCUMENT_TYPE", "Rechnung");
    await mapFolder(page, internetId, "TOPIC", "Internet");

    // Upload straight into the leaf folder.
    await page.goto("/documents");
    await page.getByTestId("upload-panel-toggle").click();
    await page.locator("#upload-folder").selectOption(internetId);
    await page.getByTestId("upload-input").setInputFiles({
      name: `Telekom Rechnung e2e ${runId}.pdf`,
      mimeType: "application/pdf",
      buffer: RUN_PDF,
    });
    await page.getByTestId("upload-submit").click();
    await expect(page.getByRole("status").first()).toContainText(/dauerhaft gespeichert/);
    const card = page.locator("[data-testid=document-card]", { hasText: `Telekom Rechnung e2e ${runId}` }).first();
    await expect(card).toBeVisible();
    await expect(card.getByTestId("document-folder")).toContainText(`${ROOT} / Rechnungen / Internet`);
    await card.getByRole("link").first().click();
    const documentUrl = page.url();

    // The document shows the owner example: invoice and internet from the folders, house as an inherited subject.
    await expect(page.getByTestId("document-folder")).toContainText(`${ROOT} / Rechnungen / Internet`);
    await expect(chip(page, "Rechnung")).toHaveAttribute("data-source", "FOLDER");
    await expect(chip(page, "Internet")).toHaveAttribute("data-source", "FOLDER");
    await expect(page.getByText(/Haus/).first()).toBeVisible();

    // The organization is the one label that is set on the document itself.
    await page.getByTestId("edit-labels").click();
    await page.getByTestId("label-picker-ORGANIZATION").fill(ORGANIZATION);
    await page.getByTestId("label-create-ORGANIZATION").click();
    await page.getByTestId("save-labels").click();
    await expect(page.getByRole("status").first()).toContainText(/Labels gespeichert|Labels saved/);
    await expect(chip(page, ORGANIZATION)).toHaveAttribute("data-source", "OWN");

    // Facets: the own organization and the inherited document type both filter, and the URL keeps the state.
    await page.goto("/documents");
    await page.getByTestId("filters-toggle").click();
    await page.getByRole("button", { name: new RegExp(ORGANIZATION) }).click();
    await expect(page).toHaveURL(/organization=/);
    await expect(page.getByTestId("result-count")).toContainText("1 Treffer");
    await expect(page.locator("[data-testid=document-card]")).toHaveCount(1);
    await page.getByRole("button", { name: /^Rechnung \d+$/ }).click();
    await expect(page).toHaveURL(/type=/);
    await expect(page.locator("[data-testid=document-card]")).toHaveCount(1);
    const filteredUrl = page.url();

    // A shared link reproduces the same filtered view, and clearing the filters brings the collection back.
    await page.goto(filteredUrl);
    await expect(page.locator("[data-testid=document-card]")).toHaveCount(1);
    await page.getByTestId("clear-filters").click();
    await expect(page.locator("[data-testid=document-card]").first()).toBeVisible();
    await expect(page).not.toHaveURL(/organization=/);

    // English shows the built-in label in English while the member-authored folder names stay unchanged.
    await page.getByTestId("language-switch").click();
    await page.goto(documentUrl);
    await expect(chip(page, "Invoice")).toBeVisible();
    await expect(page.getByTestId("document-folder")).toContainText(`${ROOT} / Rechnungen / Internet`);
    await page.getByTestId("language-switch").click();

    // Folder scope: the whole subtree and the leaf both contain the document, an unrelated scope does not.
    await page.goto(`/documents?category=${houseId}`);
    await expect(page.locator("[data-testid=document-card]")).toHaveCount(1);
    await page.goto(`/documents?category=${houseId}&subfolders=false`);
    await expect(page.locator("[data-testid=document-card]")).toHaveCount(0);
    await page.goto("/documents?category=unsorted");
    await expect(page.locator("[data-testid=document-card]", { hasText: runId })).toHaveCount(0);

    // The other member sees the same folder, the same chips and can filter the same way.
    const contextB = await browser.newContext({ locale: "de-DE" });
    const pageB = await contextB.newPage();
    await login(pageB, userB);
    await ensureGerman(pageB);
    await pageB.goto(`/documents?category=${internetId}`);
    const cardB = pageB.locator("[data-testid=document-card]", { hasText: `Telekom Rechnung e2e ${runId}` });
    await expect(cardB).toBeVisible();
    await expect(cardB.locator("[data-testid=label-chip]", { hasText: ORGANIZATION })).toBeVisible();
    await contextB.close();

    // Deleting a folder moves the document up and keeps the label it would otherwise lose.
    await page.goto("/documents/folders");
    page.once("dialog", (dialog) => void dialog.accept());
    await page.getByTestId(`delete-folder-${invoicesId}`).click();
    await expect(page.getByRole("status").first()).toContainText(/gelöscht|deleted/);
    await page.goto(documentUrl);
    await expect(page.getByTestId("document-folder")).toContainText(`${ROOT} / Internet`);
    await expect(chip(page, "Rechnung")).toHaveAttribute("data-source", "OWN");
    await expect(page.getByTestId("open-original")).toBeVisible();
  });

  test("the folder panel and the filters are usable on a phone @mobile", async ({ page }) => {
    await login(page, userA);
    await ensureGerman(page);
    await page.goto("/documents");

    await page.getByTestId("folders-toggle").click();
    await expect(page.getByRole("tree")).toBeVisible();
    await expect(page.getByTestId("folder-all")).toBeVisible();
    await expect(page.getByTestId("folder-unsorted")).toBeVisible();
    await page.getByTestId("filters-toggle").click();
    await expect(page.getByTestId("facet-filters")).toBeVisible();
    await page.getByTestId("upload-panel-toggle").click();
    await expect(page.getByTestId("upload-input")).toBeVisible();
  });

  test("the folder tree can be operated with the keyboard", async ({ page }) => {
    await login(page, userA);
    await ensureGerman(page);
    await page.goto("/documents");

    await page.getByTestId("folder-all").focus();
    await page.keyboard.press("ArrowDown");
    await expect(page.getByTestId("folder-unsorted")).toBeFocused();
    await page.keyboard.press("Enter");
    await expect(page).toHaveURL(/category=unsorted/);

    await page.getByTestId("folder-all").focus();
    await page.keyboard.press("Enter");
    await expect(page).not.toHaveURL(/category=unsorted/);
  });
});
