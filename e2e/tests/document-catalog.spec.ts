import { readFileSync } from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { expect, test, type Page } from "@playwright/test";
import { openDocument, openUpload } from "./documents";

const here = path.dirname(fileURLToPath(import.meta.url));
const FIXTURE = path.resolve(here, "../../fixtures/synthetic-contract.pdf");
// Unique bytes per run so duplicate detection of earlier runs does not short-circuit the upload.
const runId = Date.now().toString(36);
const RUN_PDF = Buffer.concat([readFileSync(FIXTURE), Buffer.from(`\n%% catalog e2e ${runId}\n`, "latin1")]);
// Every folder name carries the run id: leftovers of an earlier run must never be picked up by name.
const ROOT = `E2E Haus ${runId}`;
const INVOICES = `Rechnungen ${runId}`;
const INTERNET = `Internet ${runId}`;
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

/** The language switch lives in Settings only (issue #83); switch there if needed and return to the page. */
async function ensureGerman(page: Page) {
  const back = page.url();
  await openSettings(page);
  if ((await page.getByTestId("language-switch").textContent())?.trim() === "DE") {
    await switchLanguage(page);
    await expect(page.getByTestId("language-switch")).toHaveText("EN");
  }
  await page.goto(back);
}

async function openSettings(page: Page) {
  await page.goto("/settings");
  await expect(page.getByText(/Angemeldet als|Signed in as/)).toBeVisible();
}

/**
 * Toggles the language in Settings and waits until the member preference is stored, so a following navigation
 * keeps it. The caller navigates on afterwards.
 */
async function switchLanguage(page: Page) {
  await openSettings(page);
  const saved = page.waitForResponse((response) => response.url().endsWith("/api/members/me/locale") && response.ok());
  await page.getByTestId("language-switch").click();
  await saved;
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
    // Scoped to the autocomplete list: native select options of the folder pickers also expose the option role.
    await row.getByTestId(`label-options-${facet}`).getByRole("option", { name: labelName }).first().click();
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

/** The folder line of the open document detail page. */
function folderOf(page: Page) {
  return page.getByTestId("document-folder").first();
}

test.describe("document tree, labels and faceted search", () => {
  test.skip(!userA.password || !userB.password, "AEGIS_PASSWORD_A and AEGIS_PASSWORD_B must be set");

  test("folders carry labels, documents inherit them and facets find them", async ({ page, browser }) => {
    test.setTimeout(600_000);
    await login(page, userA);
    await ensureGerman(page);

    // A small household structure with explicit folder labels: House -> Invoices -> Internet.
    const houseId = await createFolder(page, ROOT, null);
    const invoicesId = await createFolder(page, INVOICES, houseId);
    const internetId = await createFolder(page, INTERNET, invoicesId);
    await mapFolder(page, houseId, "DOCUMENT_TYPE", undefined, "HOUSE");
    await mapFolder(page, invoicesId, "DOCUMENT_TYPE", "Rechnung");
    await mapFolder(page, internetId, "TOPIC", "Internet");

    // Upload straight into the leaf folder.
    await page.goto("/documents");
    await openUpload(page);
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
    await expect(card.getByTestId("document-folder")).toContainText(`${ROOT} / ${INVOICES} / ${INTERNET}`);
    await openDocument(card);
    // The SPA changes the URL before it renders the detail page, so wait for the heading of that document.
    await expect(page.getByRole("heading", { level: 1 })).toContainText(`Telekom Rechnung e2e ${runId}`);
    const documentUrl = page.url();

    // The document shows the owner example: invoice and internet from the folders, house as an inherited subject.
    await expect(folderOf(page)).toContainText(`${ROOT} / ${INVOICES} / ${INTERNET}`);
    await expect(chip(page, "Rechnung")).toHaveAttribute("data-source", "FOLDER");
    await expect(chip(page, "Internet")).toHaveAttribute("data-source", "FOLDER");
    await expect(page.getByText(/Haus/).first()).toBeVisible();

    // The organization is the one label that is set on the document itself.
    await page.getByTestId("edit-labels").click();
    await page.getByTestId("label-picker-ORGANIZATION").fill(ORGANIZATION);
    await page.getByTestId("label-create-ORGANIZATION").click();
    await expect(page.getByTestId("label-editor").getByText(ORGANIZATION)).toBeVisible();
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
    await switchLanguage(page);
    await page.goto(documentUrl);
    await expect(chip(page, "Invoice")).toBeVisible();
    await expect(folderOf(page)).toContainText(`${ROOT} / ${INVOICES} / ${INTERNET}`);
    await switchLanguage(page);

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
    await page.getByTestId(`edit-folder-${invoicesId}`).click();
    page.once("dialog", (dialog) => void dialog.accept());
    await page.getByTestId(`delete-folder-${invoicesId}`).click();
    await expect(page.getByRole("status").first()).toContainText(/gelöscht|deleted/);
    await page.goto(documentUrl);
    await expect(folderOf(page)).toContainText(`${ROOT} / ${INTERNET}`);
    await expect(chip(page, "Rechnung")).toHaveAttribute("data-source", "OWN");
    await expect(page.getByTestId("open-original")).toBeVisible();
  });

  test("folders and labels are read-only rows; rename, merge, retire and delete work from their forms", async ({ page }) => {
    test.setTimeout(180_000);
    await login(page, userA);
    await ensureGerman(page);

    // Folders: one create button, no example structure, and a row opens its form only on "Bearbeiten" (issue #83).
    const folderName = `Zeilen e2e ${runId}`;
    const folderId = await createFolder(page, folderName, null);
    await expect(page.getByRole("button", { name: "Ordner anlegen" })).toHaveCount(1);
    await expect(page.getByText("Beispielstruktur")).toHaveCount(0);
    await expect(page.getByRole("button", { name: "Speichern" })).toHaveCount(0);
    const folder = page.locator(`[data-folder-id="${folderId}"]`);
    await expect(folder.getByTestId("save-implications")).toHaveCount(0);
    await expect(page.getByTestId(`edit-folder-${folderId}`)).toHaveText("Bearbeiten");
    await page.getByTestId(`edit-folder-${folderId}`).click();
    await folder.getByTestId("folder-name-input").fill(`${folderName} neu`);
    await folder.getByTestId("save-folder-name").click();
    await expect(page.getByRole("status").first()).toContainText("Ordner umbenannt");
    await expect(folder).toContainText(`${folderName} neu`);
    page.once("dialog", (dialog) => void dialog.accept());
    await page.getByTestId(`delete-folder-${folderId}`).click();
    await expect(page.getByRole("status").first()).toContainText("gelöscht");

    // Labels: one create button per group and no open forms.
    await page.goto("/documents/labels");
    await expect(page.getByRole("button", { name: "Label anlegen" })).toHaveCount(4);
    await expect(page.getByRole("button", { name: "Speichern" })).toHaveCount(0);
    const createTag = async (name: string) => {
      await page.getByTestId("new-label-TAG").fill(name);
      await page.getByTestId("create-label-TAG").click();
      const row = page.locator("[data-testid=label-row]", { hasText: name }).first();
      await expect(row).toBeVisible();
      return (await row.getAttribute("data-label-id"))!;
    };
    const source = `Quelle e2e ${runId}`;
    const target = `Ziel e2e ${runId}`;
    const sourceId = await createTag(source);
    const targetId = await createTag(target);

    const sourceRow = page.locator(`[data-label-id="${sourceId}"]`);
    await page.getByTestId(`edit-label-${sourceId}`).click();
    await sourceRow.getByTestId("label-name-input").fill(`${source} neu`);
    await sourceRow.getByTestId("save-label-name").click();
    await expect(page.getByRole("status").first()).toContainText(`In „${source} neu“ umbenannt.`);
    await sourceRow.getByTestId("merge-target").selectOption({ label: target });
    await sourceRow.getByTestId("merge-label").click();
    await expect(page.getByRole("status").first()).toContainText("zusammengeführt");
    await expect(sourceRow).toContainText("Zusammengeführt");

    const targetRow = page.locator(`[data-label-id="${targetId}"]`);
    await page.getByTestId(`edit-label-${targetId}`).click();
    await targetRow.getByTestId("retire-label").click();
    await expect(targetRow).toContainText("Stillgelegt");
    await targetRow.getByTestId("reactivate-label").click();
    await expect(targetRow).not.toContainText("Stillgelegt");
    // A merge target stays referenced, so deletion is shown on an unused label.
    const spareId = await createTag(`Ersatz e2e ${runId}`);
    const spareRow = page.locator(`[data-label-id="${spareId}"]`);
    await page.getByTestId(`edit-label-${spareId}`).click();
    await spareRow.getByTestId("delete-label").click();
    await expect(page.getByRole("status").first()).toContainText("Label gelöscht.");
    await expect(spareRow).toHaveCount(0);
  });

  test("the folder tree in the sidebar can be operated with the keyboard", async ({ page }) => {
    test.skip((page.viewportSize()?.width ?? 0) < 1024, "the sidebar starts at 1024 px");
    await login(page, userA);
    await ensureGerman(page);
    await page.goto("/documents");

    const items = page.getByTestId("folder-tree").getByRole("treeitem");
    await items.first().focus();
    await page.keyboard.press("End");
    await expect(items.last()).toBeFocused();
    await page.keyboard.press("Enter");
    await expect(page).toHaveURL(/category=/);
    await expect(items.last()).toHaveAttribute("aria-selected", "true");

    await page.keyboard.press("Home");
    await expect(items.first()).toBeFocused();
  });
});

test.describe("document folders on a phone", () => {
  test.skip(!userA.password, "AEGIS_PASSWORD_A must be set");
  test.use({ viewport: { width: 375, height: 812 } });

  test("the folder panel and the filters are reachable @mobile", async ({ page }) => {
    await login(page, userA);
    await ensureGerman(page);
    await page.goto("/documents");

    // The folder rail with "Manage folders" at its end (issue #27, #108).
    const rail = page.getByTestId("folder-rail");
    await expect(rail).toBeVisible();
    await expect(rail.getByTestId("folder-all")).toHaveAttribute("aria-pressed", "true");
    await expect(rail.getByTestId("manage-folders")).toHaveAttribute("href", "/documents/folders");
    // "Manage labels" sits in the filter; the trash link closes the list.
    await page.getByTestId("filters-toggle").click();
    await expect(page.getByTestId("facet-filters")).toBeVisible();
    await expect(page.getByTestId("manage-labels")).toHaveAttribute("href", "/documents/labels");
    await page.keyboard.press("Escape");
    await expect(page.getByTestId("filter-sheet")).not.toHaveAttribute("open", "");
    await expect(page.getByTestId("open-trash")).toHaveAttribute("href", "/trash");
    await openUpload(page);
    // The file inputs are hidden; the camera, gallery and file buttons open them.
    await expect(page.getByTestId("upload-take-photo")).toBeVisible();
    await expect(page.getByTestId("upload-choose-file")).toBeVisible();
  });
});
