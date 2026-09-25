import { readFileSync } from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { expect, test, type Page } from "@playwright/test";

const here = path.dirname(fileURLToPath(import.meta.url));
const FIXTURE = path.resolve(here, "../../fixtures/synthetic-contract.pdf");
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

test.describe("recoverable trash", () => {
  test.skip(!userA.password, "AEGIS_PASSWORD_A must be set");

  test("a document goes to the trash, leaves the collection and comes back from the trash page @mobile", async ({ page }, info) => {
    test.setTimeout(420_000);
    // Unique bytes per run and viewport, so duplicate detection of another run does not short-circuit the upload.
    const title = `Papierkorb e2e ${runId} ${info.project.name}`;
    const pdf = Buffer.concat([readFileSync(FIXTURE), Buffer.from(`\n%% ${title}\n`, "latin1")]);
    await login(page, userA);
    await ensureGerman(page);

    await page.goto("/documents");
    await page.getByTestId("upload-panel-toggle").click();
    await page.getByTestId("upload-input").setInputFiles({ name: `${title}.pdf`, mimeType: "application/pdf", buffer: pdf });
    await page.getByTestId("upload-submit").click();
    const card = page.locator("[data-testid=document-card]", { hasText: title }).first();
    await expect(card).toBeVisible();
    await card.getByRole("link").first().click();
    await expect(page.getByTestId("processing-state")).toContainText("Archiviert", { timeout: 300_000 });

    // Someone else changed the document first: the member is told so, and the reload that follows keeps the message.
    await page.route(
      "**/api/documents/*/trash",
      (route) =>
        route.fulfill({
          status: 409,
          contentType: "application/problem+json",
          body: JSON.stringify({ status: 409, title: "Conflict", code: "version.conflict", resource: "document" }),
        }),
      { times: 1 },
    );
    let conflicted = false;
    const reloaded = page.waitForResponse((response) => {
      const pathname = new URL(response.url()).pathname;
      if (pathname.endsWith("/trash")) conflicted = true;
      return conflicted && response.request().method() === "GET" && /^\/api\/documents\/[^/]+$/.test(pathname);
    });
    await page.getByTestId("trash-document").click();
    await page.getByTestId("trash-document-confirm").click();
    await reloaded;
    // The page applies the reload right after its answers arrive; the message must survive that, not just flash.
    await page.waitForTimeout(1_000);
    await expect(page.getByRole("alert")).toContainText("inzwischen geändert");
    await expect(page.getByTestId("trash-banner")).toHaveCount(0);

    // A first tap only asks; the confirmation moves the document. The earlier message is gone.
    await page.getByTestId("trash-document").click();
    await page.getByTestId("trash-document-confirm").click();
    await expect(page.getByTestId("trash-banner")).toContainText("Im Papierkorb");
    await expect(page.getByRole("alert")).toHaveCount(0);
    await expect(page.getByTestId("rename-document")).toHaveCount(0);

    await page.goto("/documents");
    await page.getByTestId("search-input").fill(title);
    await page.getByRole("button", { name: "Suchen" }).click();
    await expect(page.locator("[data-testid=document-card]", { hasText: title })).toHaveCount(0);

    await page.goto("/trash");
    const entry = page.locator("[data-testid=trash-entry]", { hasText: title });
    await expect(entry).toContainText("Wiederherstellbar mindestens bis");
    await entry.getByTestId("trash-entry-restore").click();
    await expect(page.getByRole("status").first()).toContainText("wurde wiederhergestellt");
    await expect(page.locator("[data-testid=trash-entry]", { hasText: title })).toHaveCount(0);

    await page.goto("/documents");
    await page.getByTestId("search-input").fill(title);
    await page.getByRole("button", { name: "Suchen" }).click();
    await expect(page.locator("[data-testid=document-card]", { hasText: title })).toBeVisible();
  });
});
