import { defineConfig, devices } from "@playwright/test";

/**
 * Browser smoke test of the first runnable test. Runs against a deployed stack (LAN preview or CI compose):
 *   AEGIS_BASE_URL=http://<aegis-host> AEGIS_USER_A=anna AEGIS_PASSWORD_A=... AEGIS_USER_B=ben AEGIS_PASSWORD_B=...
 */
export default defineConfig({
  testDir: "./tests",
  timeout: 240_000,
  expect: { timeout: 15_000 },
  fullyParallel: false,
  workers: 1,
  retries: 0,
  reporter: [["list"], ["html", { open: "never", outputFolder: "playwright-report" }]],
  use: {
    baseURL: process.env.AEGIS_BASE_URL ?? "http://localhost",
    // CI runs against plain HTTP. Set AEGIS_INSECURE_TLS=true only for a LAN run against the household
    // certificate authority, which the test runner does not trust.
    ignoreHTTPSErrors: process.env.AEGIS_INSECURE_TLS === "true",
    trace: "retain-on-failure",
    screenshot: "only-on-failure",
    video: "off",
    locale: "de-DE",
  },
  projects: [
    { name: "desktop-chromium", use: { ...devices["Desktop Chrome"] } },
    { name: "mobile-chromium", use: { ...devices["Pixel 7"] }, grep: /@mobile/ },
  ],
});
