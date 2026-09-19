import { readFileSync } from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { expect, test, type Page } from "@playwright/test";

const here = path.dirname(fileURLToPath(import.meta.url));
const FIXTURE = path.resolve(here, "../../fixtures/synthetic-contract.pdf");
const runId = Date.now().toString(36);
// Unique bytes per run, so the first save is new and the repeated share is recognized as the same file.
const RUN_PDF = Buffer.concat([readFileSync(FIXTURE), Buffer.from(`\n%% e2e share ${runId}\n`, "latin1")]);
const PDF_NAME = `Geteilt e2e ${runId}.pdf`;

const user = { username: process.env.AEGIS_USER_A ?? "anna", password: process.env.AEGIS_PASSWORD_A ?? "" };

interface SharedPayload {
  name: string;
  type: string;
  base64: string;
}

/**
 * Sends the multipart POST that Android's share sheet makes to the manifest's share_target. A fetch from a
 * controlled page reaches the service worker the same way as the share navigation does.
 */
async function share(page: Page, files: SharedPayload[]): Promise<string> {
  // The worker registers on the first load and takes control of the open page once it is active.
  await page.waitForFunction(() => navigator.serviceWorker?.controller != null);
  return page.evaluate(async (payload) => {
    const form = new FormData();
    for (const file of payload) {
      const bytes = Uint8Array.from(atob(file.base64), (c) => c.charCodeAt(0));
      form.append("files", new File([bytes], file.name, { type: file.type }));
    }
    const response = await fetch("/share-target", { method: "POST", body: form });
    return new URL(response.url).pathname + new URL(response.url).search;
  }, files);
}

async function signIn(page: Page) {
  await page.getByLabel(/Benutzername|Username/).fill(user.username);
  await page.getByLabel(/Passwort|Password/).fill(user.password);
  await page.getByRole("button", { name: /^(Anmelden|Sign in)$/ }).click();
}

const pdf: SharedPayload = { name: PDF_NAME, type: "application/pdf", base64: RUN_PDF.toString("base64") };
const photo: SharedPayload = { name: "Foto.jpg", type: "image/jpeg", base64: Buffer.from("not a pdf").toString("base64") };

test.describe("Android share target", () => {
  test.skip(!user.password, "AEGIS_PASSWORD_A must be set");

  test("the app is installable: manifest, share target and icons are served", async ({ request }) => {
    const manifest = await request.get("/manifest.webmanifest");
    expect(manifest.ok()).toBeTruthy();
    expect(manifest.headers()["content-type"]).toContain("application/manifest+json");
    const body = await manifest.json();
    expect(body.share_target).toMatchObject({ action: "/share-target", method: "POST", enctype: "multipart/form-data" });
    // Chrome drops files that do not match; Aegis takes any file and sorts it out on the share page.
    expect(body.share_target.params.files[0].accept).toContain("*/*");
    for (const icon of body.icons as { src: string }[]) {
      const response = await request.get(icon.src);
      expect(response.headers()["content-type"], icon.src).toContain("image/png");
    }
    const worker = await request.get("/sw.js");
    expect(worker.headers()["content-type"]).toContain("javascript");
  });

  test("a shared PDF survives the login, is saved once and a repeated share creates no duplicate", async ({ page, context }) => {
    // Not signed in: the share is kept and the login page explains why it appears.
    await page.goto("/login");
    const target = await share(page, [pdf, photo]);
    expect(target).toBe("/share?received=2");
    await page.goto(target);
    await expect(page).toHaveURL(/\/login$/);
    await expect(page.getByRole("status")).toContainText(/geteilten Dateien|files you shared/);
    await signIn(page);
    await expect(page).toHaveURL(/\/share\?received=2$/);

    const files = page.getByTestId("share-file");
    await expect(files).toHaveCount(2);
    const shared = files.filter({ hasText: PDF_NAME });
    const rejected = files.filter({ hasText: "Foto.jpg" });
    await expect(rejected.getByRole("alert")).toContainText(/Nur PDF|Only PDF/);

    // An expired server session during the save keeps the file and returns here after the next login.
    await context.clearCookies({ name: "AEGIS_SESSION" });
    await page.getByTestId("share-save").click();
    await expect(page).toHaveURL(/\/login$/);
    await signIn(page);
    await expect(page).toHaveURL(/\/share/);
    await expect(files.filter({ hasText: PDF_NAME })).toHaveCount(1);

    await page.getByTestId("share-save").click();
    await expect(shared.locator("[data-state=SUCCEEDED]")).toBeVisible();
    await expect(shared).toContainText(/Gespeichert als|Stored as/);
    await expect(page.getByTestId("share-done")).toBeVisible();

    // Reloading does not upload again: saved files have left the inbox; the rejected one waits to be discarded.
    await page.reload();
    await expect(files).toHaveCount(1);
    await rejected.getByRole("button", { name: /Verwerfen|Discard/ }).click();
    await expect(files).toHaveCount(0);

    // Sharing the same file again is answered with the existing document instead of a duplicate.
    await share(page, [pdf]);
    await page.goto("/share?received=1");
    await page.getByTestId("share-save").click();
    await expect(shared).toContainText(/Schon in Aegis|Already in Aegis/);
    await shared.getByRole("link", { name: /Dokument öffnen|Open document/ }).click();
    await expect(page).toHaveURL(/\/documents\/[^/?]+$/);
  });
});
