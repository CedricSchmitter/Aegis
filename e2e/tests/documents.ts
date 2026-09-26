import { expect, type Locator, type Page } from "@playwright/test";

/**
 * Shared steps on the document screens (issue #27). The phone and the desktop layout differ: the phone has its own
 * search field and an upload button, the desktop uses the header search and the drop target at the end of the list.
 */

/** Below 1024 px the phone shell is shown. */
export function isPhoneWidth(page: Page): boolean {
  return (page.viewportSize()?.width ?? 1280) < 1024;
}

/** Opens the upload sheet unless it is open already, for example after "?upload=1" or a restored scan. */
export async function openUpload(page: Page) {
  const panel = page.getByTestId("upload-panel");
  const toggle = page.getByTestId("upload-panel-toggle");
  await expect(toggle).toBeVisible();
  if (!(await panel.evaluate((element) => (element as HTMLDialogElement).open))) {
    await toggle.click();
  }
  await expect(panel).toHaveAttribute("open", "");
}

/**
 * Opens a listed document. On a desktop from 1280 px a mouse click only shows it in the context panel, so the
 * title link is activated with Enter, which opens it at every width.
 */
export async function openDocument(card: Locator) {
  await card.getByRole("link").first().press("Enter");
}

/** Searches the document list with the page's field on a phone or the header search on a desktop. */
export async function searchDocuments(page: Page, text: string) {
  const field = isPhoneWidth(page)
    ? page.getByTestId("search-input")
    : page.getByTestId("header-search").getByRole("searchbox");
  await field.fill(text);
  await field.press("Enter");
  await expect(page).toHaveURL(/[?&]query=/);
}
