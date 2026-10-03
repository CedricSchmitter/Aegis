// Quick screenshots of every page in both viewports, to check the mocked data before recording.
import { chromium } from "playwright";
const base = process.env.BASE ?? "http://localhost:4173";
const out = process.argv[2] ?? "out/shots";
const paths = (process.argv[3] ?? "/today,/documents,/documents/d-strom-vertrag,/contracts,/contracts/k-irongrid,/tasks,/chat,/status").split(",");
const browser = await chromium.launch({ executablePath: "/opt/pw-browsers/chromium" });
for (const [name, opts] of [
  ["desk", { viewport: { width: 1600, height: 900 }, deviceScaleFactor: 1.2 }],
  ["phone", { viewport: { width: 412, height: 892 }, deviceScaleFactor: 2.625, isMobile: true, hasTouch: true }],
]) {
  const ctx = await browser.newContext({ ...opts, locale: "de-DE", timezoneId: "Europe/Berlin" });
  const page = await ctx.newPage();
  page.on("console", (m) => m.type() === "error" && console.log(name, "console:", m.text()));
  page.on("pageerror", (e) => console.log(name, "pageerror:", e.message));
  for (const p of paths) {
    await page.goto(base + p);
    await page.waitForTimeout(1500);
    await page.screenshot({ path: `${out}/${name}${p.replaceAll("/", "_")}.png` });
  }
  await ctx.close();
}
await browser.close();
