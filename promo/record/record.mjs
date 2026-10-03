// Records the promo scenes from the real frontend against the mock API. Each scene becomes
// recordings/<scene>.mp4 (constant 30 fps) built from full-resolution screenshots taken in a loop, which are sharper
// than Playwright's built-in video. Usage: node record/record.mjs [scene ...]
import { chromium } from "playwright";
import { mkdir, rm, writeFile } from "node:fs/promises";
import { execFileSync } from "node:child_process";

const BASE = process.env.BASE ?? "http://localhost:4173";
const OUT = new URL("../recordings/", import.meta.url).pathname;
const PAGES = new URL("../out/pages/", import.meta.url).pathname;

// The page runs in slow motion (timers, clocks, animations and the mock API all slowed by SLOW) while frames are
// captured, then the frames are played back at full speed. Screenshots at full resolution take longer than a frame,
// so this keeps the result smooth.
const SLOW = Number(process.env.SLOW ?? 5);
const SLOW_TIME = `(() => {
  const S = ${SLOW};
  const realNow = Date.now.bind(Date);
  const realPerf = performance.now.bind(performance);
  const d0 = realNow();
  const p0 = realPerf();
  const now = () => d0 + (realNow() - d0) / S;
  const RealDate = Date;
  class SlowDate extends RealDate {
    constructor(...args) { if (args.length === 0) super(now()); else super(...args); }
    static now() { return now(); }
  }
  window.Date = SlowDate;
  performance.now = () => p0 + (realPerf() - p0) / S;
  const realTimeout = window.setTimeout.bind(window);
  const realInterval = window.setInterval.bind(window);
  window.setTimeout = (fn, ms = 0, ...rest) => realTimeout(fn, ms * S, ...rest);
  window.setInterval = (fn, ms = 0, ...rest) => realInterval(fn, ms * S, ...rest);
  const realFrame = window.requestAnimationFrame.bind(window);
  window.requestAnimationFrame = (cb) => realFrame((t) => cb(p0 + (t - p0) / S));
})();`;

const DESKTOP = { viewport: { width: 1440, height: 900 }, deviceScaleFactor: 4 / 3 };
const PHONE = { viewport: { width: 412, height: 892 }, deviceScaleFactor: 2.625, isMobile: true, hasTouch: true };

// A visible cursor and tap ripples, since screen captures do not include the pointer.
const POINTER = `
addEventListener("DOMContentLoaded", () => {
  const style = document.createElement("style");
  style.textContent = \`
    #promo-cursor { position: fixed; z-index: 2147483647; pointer-events: none; width: 22px; height: 22px; margin: -3px 0 0 -3px;
      background: url("data:image/svg+xml,%3Csvg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 24 24'%3E%3Cpath d='M3 2l7 19 2.5-7.5L20 11z' fill='%23fcee0a' stroke='%23000' stroke-width='1.5'/%3E%3C/svg%3E") no-repeat;
      filter: drop-shadow(0 0 6px rgba(252,238,10,.8)); transition: transform .08s; display: none; }
    .promo-tap { position: fixed; z-index: 2147483646; pointer-events: none; width: 16px; height: 16px; margin: -8px 0 0 -8px; border-radius: 50%;
      border: 3px solid #00f0ff; box-shadow: 0 0 18px #00f0ff; animation: promo-tap .6s ease-out forwards; }
    @keyframes promo-tap { to { transform: scale(4.5); opacity: 0; } }\`;
  document.head.append(style);
  const cursor = document.createElement("div");
  cursor.id = "promo-cursor";
  document.body.append(cursor);
  addEventListener("mousemove", (e) => { if (window.__promoCursor) { cursor.style.display = "block"; cursor.style.left = e.clientX + "px"; cursor.style.top = e.clientY + "px"; } }, true);
  addEventListener("pointerdown", (e) => {
    const tap = document.createElement("div");
    tap.className = "promo-tap"; tap.style.left = e.clientX + "px"; tap.style.top = e.clientY + "px";
    document.body.append(tap); setTimeout(() => tap.remove(), 700);
    cursor.style.transform = "scale(.85)"; setTimeout(() => (cursor.style.transform = ""), 120);
  }, true);
});`;

async function scene(browser, name, options, run) {
  const context = await browser.newContext({ ...options, locale: "de-DE", timezoneId: "Europe/Berlin", colorScheme: "dark" });
  await context.addInitScript(SLOW_TIME);
  await context.addInitScript(POINTER);
  if (!options.isMobile) await context.addInitScript("window.__promoCursor = true");
  const page = await context.newPage();
  await fetch(`${BASE}/__promo/reset?slow=${SLOW}`, { method: "POST" });
  page.on("pageerror", (e) => console.log(name, "pageerror:", e.message));

  // Screencast frames come at CSS pixel size in headless mode, so frames are full-resolution screenshots taken in a
  // loop; each one keeps its capture time.
  const frames = [];
  const cdp = await context.newCDPSession(page);
  await cdp.send("Animation.enable");
  await cdp.send("Animation.setPlaybackRate", { playbackRate: 1 / SLOW });
  const { viewport, deviceScaleFactor } = options;
  const clip = { x: 0, y: 0, width: viewport.width, height: viewport.height, scale: deviceScaleFactor };
  let loop = null;
  let recording = false;
  const start = async () => {
    recording = true;
    loop = (async () => {
      while (recording) {
        const before = Date.now() / 1000;
        try {
          // Without a scaled clip the capture comes at CSS pixel size.
          // The clip is in document coordinates, so it follows the current scroll position.
          const { cssVisualViewport: view } = await cdp.send("Page.getLayoutMetrics");
          const at = { ...clip, x: view.pageX, y: view.pageY };
          const { data } = await cdp.send("Page.captureScreenshot", { format: "jpeg", quality: 90, optimizeForSpeed: true, clip: at });
          frames.push({ data, t: (before + Date.now() / 1000) / 2 });
        } catch {
          await new Promise((resolve) => setTimeout(resolve, 20));
        }
      }
    })();
  };
  await run(page, start);
  const end = Date.now() / 1000;
  recording = false;
  await loop;
  await context.close();

  const dir = `${OUT}${name}/`;
  await rm(dir, { recursive: true, force: true });
  await mkdir(dir, { recursive: true });
  let list = "";
  for (let i = 0; i < frames.length; i++) {
    const file = `${String(i).padStart(5, "0")}.jpg`;
    await writeFile(dir + file, Buffer.from(frames[i].data, "base64"));
    const next = i + 1 < frames.length ? frames[i + 1].t : end;
    list += `file '${file}'\nduration ${Math.max(0.001, (next - frames[i].t) / SLOW).toFixed(4)}\n`;
  }
  list += `file '${String(frames.length - 1).padStart(5, "0")}.jpg'\n`;
  await writeFile(dir + "list.txt", list);
  execFileSync("ffmpeg", ["-y", "-loglevel", "error", "-f", "concat", "-safe", "0", "-i", dir + "list.txt", "-vf", "fps=30,scale=trunc(iw/2)*2:trunc(ih/2)*2", "-c:v", "libx264", "-crf", "14", "-preset", "slow", "-pix_fmt", "yuv420p", `${OUT}${name}.mp4`]);
  const seconds = ((end - frames[0].t) / SLOW).toFixed(1);
  console.log(`${name}: ${frames.length} frames, ${seconds}s`);
}

const pause = (page, ms) => page.waitForTimeout(ms * SLOW);

/** Moves the visible cursor in slow motion. */
const move = (page, x, y, steps = 25) => page.mouse.move(x, y, { steps: steps * SLOW });

/** Scrolls with the wheel in small steps, so the slowed recording shows a smooth scroll. */
async function scroll(page, dy, ms = 500) {
  const steps = Math.round((ms / 16) * SLOW);
  for (let i = 0; i < steps; i++) {
    await page.mouse.wheel(0, dy / steps);
    await page.waitForTimeout(16);
  }
}

/** Moves the visible cursor smoothly to an element and clicks it. */
async function glideClick(page, locator, { steps = 25, delay = 250 } = {}) {
  const box = await locator.boundingBox();
  await move(page, box.x + box.width / 2, box.y + box.height / 2, steps);
  await pause(page, delay);
  await locator.click();
}

async function typeSlowly(locator, text, delay = 38) {
  await locator.pressSequentially(text, { delay: delay * SLOW });
}

const SCENES = {
  // Today on the desktop: tasks and the cancellation countdowns.
  "desk-today": (b) =>
    scene(b, "desk-today", DESKTOP, async (page, start) => {
      await page.goto(`${BASE}/today`);
      await page.mouse.move(900, 600);
      await page.getByText("Iron Grid Fitness").first().waitFor();
      await pause(page, 600);
      await start();
      await pause(page, 600);
      await move(page, 1200, 200, 40);
      await move(page, 1290, 170, 30);
      await pause(page, 1200);
      await glideClick(page, page.getByText("Iron Grid Fitness").first());
      await page.getByText("Kündigungstermin").first().waitFor();
      await pause(page, 1500);
      await scroll(page, 500);
      await pause(page, 1800);
    }),

  // Asking the household assistant on the desktop: progress steps, then an answer with verified sources.
  "desk-chat": (b) =>
    scene(b, "desk-chat", DESKTOP, async (page, start) => {
      await page.goto(`${BASE}/chat`);
      await page.getByTestId("chat-input").waitFor();
      await page.mouse.move(1000, 700);
      await pause(page, 500);
      await start();
      await pause(page, 500);
      await glideClick(page, page.getByTestId("chat-input"));
      await typeSlowly(page.getByTestId("chat-input"), "Bis wann müssen wir den Stromvertrag kündigen, und was zahlen wir gerade?");
      await pause(page, 400);
      await glideClick(page, page.getByRole("button", { name: "Fragen", exact: true }).last(), { steps: 15 });
      await page.getByTestId("chat-answer").waitFor({ timeout: 180000 });
      await pause(page, 1500);
      await scroll(page, 400);
      await pause(page, 2500);
      await scroll(page, 400);
      await pause(page, 2500);
    }),

  // Uploading a photo of a letter on the phone; the archive and the AI classification follow live.
  "phone-upload": (b) =>
    scene(b, "phone-upload", PHONE, async (page, start) => {
      await page.goto(`${BASE}/documents`);
      await page.getByTestId("document-card").first().waitFor();
      await pause(page, 500);
      await start();
      await pause(page, 700);
      await page.getByRole("button", { name: /Hochladen/i }).first().tap();
      await pause(page, 900);
      await page.getByTestId("upload-camera-input").setInputFiles(`${PAGES}upload-photo.jpg`);
      // The corner editor shows the detected sheet; confirming it straightens the page.
      const confirm = page.getByRole("button", { name: "OK", exact: true });
      await confirm.waitFor({ timeout: 180000 });
      await pause(page, 2200);
      await confirm.tap();
      await page.getByTestId("scan-page").first().waitFor({ timeout: 180000 });
      await page.waitForFunction(() => !document.querySelector("[data-testid=upload-submit]")?.disabled, null, { timeout: 180000 });
      await pause(page, 1500);
      await page.getByTestId("upload-submit").tap();
      await page.getByTestId("document-card").first().waitFor();
      // The list refreshes every four seconds until the classification is done.
      await page.getByText("Beitragsanpassung Kfz-Versicherung 2027").first().waitFor({ timeout: 180000 });
      await pause(page, 1800);
      await page.getByText("Beitragsanpassung Kfz-Versicherung 2027").first().tap();
      await page.getByTestId("document-preview").waitFor();
      await pause(page, 1500);
      await scroll(page, 600);
      await pause(page, 2000);
    }),

  // The phone home screen the push notification lands on, then the opened task.
  "phone-today": (b) =>
    scene(b, "phone-today", PHONE, async (page, start) => {
      await page.goto(`${BASE}/today`);
      await page.getByText("Iron Grid Fitness").first().waitFor();
      await pause(page, 500);
      await start();
      await pause(page, 4000);
      await page.goto(`${BASE}/tasks/t-fitness`);
      await page.getByText("Fitnessstudio kündigen").first().waitFor();
      await pause(page, 1500);
      await scroll(page, 300, 900);
      await pause(page, 3000);
    }),

  // The assistant on the phone, for a split-screen moment.
  "phone-chat": (b) =>
    scene(b, "phone-chat", PHONE, async (page, start) => {
      await page.goto(`${BASE}/chat`);
      await page.getByTestId("chat-input").waitFor();
      await pause(page, 500);
      await start();
      await pause(page, 400);
      await page.getByTestId("chat-input").tap();
      await typeSlowly(page.getByTestId("chat-input"), "Was ist diese Woche wichtig?", 45);
      await page.getByRole("button", { name: "Fragen", exact: true }).last().tap();
      await page.getByTestId("chat-answer").waitFor({ timeout: 180000 });
      await pause(page, 3500);
    }),

  // The page editor on the phone: a mailbox scan with two letters is split, a sideways page turned, both renamed.
  "phone-pages": (b) =>
    scene(b, "phone-pages", PHONE, async (page, start) => {
      await page.goto(`${BASE}/documents/d-post`);
      await page.getByTestId("edit-pages").waitFor();
      await pause(page, 500);
      await start();
      await pause(page, 600);
      await page.getByTestId("edit-pages").tap();
      await page.getByTestId("page-edit-split").first().waitFor();
      await pause(page, 900);
      await scroll(page, 380, 600);
      await pause(page, 500);
      await page.getByTestId("page-edit-split").first().tap();
      await pause(page, 900);
      const second = page.getByTestId("page-edit-output").nth(1);
      await second.getByTestId("page-edit-rotate").first().scrollIntoViewIfNeeded();
      await pause(page, 500);
      await second.getByTestId("page-edit-rotate").first().tap();
      await pause(page, 900);
      const titles = page.getByTestId("page-edit-title");
      await titles.nth(1).scrollIntoViewIfNeeded();
      await titles.nth(1).fill("");
      await typeSlowly(titles.nth(1), "Rechnung Zahnarzt", 35);
      await pause(page, 500);
      await page.getByTestId("page-edit-submit").scrollIntoViewIfNeeded();
      await pause(page, 400);
      await page.getByTestId("page-edit-submit").tap();
      await page.getByText("Fertig: 2 neue Dokumente").waitFor({ timeout: 180000 });
      await pause(page, 2500);
    }),

  // Full-text search from the header on the desktop.
  "desk-search": (b) =>
    scene(b, "desk-search", DESKTOP, async (page, start) => {
      await page.goto(`${BASE}/today`);
      await page.getByText("Iron Grid Fitness").first().waitFor();
      await page.mouse.move(800, 500);
      await pause(page, 400);
      await start();
      await pause(page, 300);
      const search = page.getByTestId("header-search").locator("input");
      await glideClick(page, search, { steps: 18, delay: 150 });
      await typeSlowly(search, "Kündigung", 55);
      await pause(page, 300);
      await search.press("Enter");
      await page.getByText("Glasfaser-Vertrag NovaNet 1000").first().waitFor();
      await pause(page, 2500);
    }),

  // Switching the colour palette in the settings.
  "desk-theme": (b) =>
    scene(b, "desk-theme", DESKTOP, async (page, start) => {
      await page.goto(`${BASE}/settings`);
      await page.getByTestId("appearance-settings").waitFor();
      await page.getByTestId("appearance-settings").scrollIntoViewIfNeeded();
      await page.mouse.move(900, 500);
      await pause(page, 400);
      await start();
      await pause(page, 400);
      for (const name of ["Corpo", "Nomad", "Streetkid"]) {
        await glideClick(page, page.getByTestId("appearance-settings").getByText(name, { exact: true }), { steps: 14, delay: 150 });
        await pause(page, 1300);
      }
    }),

  // Status: archive, AI, backup and disks at a glance.
  "desk-status": (b) =>
    scene(b, "desk-status", DESKTOP, async (page, start) => {
      await page.goto(`${BASE}/status`);
      await page.mouse.move(900, 500);
      await pause(page, 1200);
      await start();
      await pause(page, 1200);
      await scroll(page, 500, 900);
      await pause(page, 1500);
    }),

  // Documents on the desktop with the context panel.
  "desk-documents": (b) =>
    scene(b, "desk-documents", DESKTOP, async (page, start) => {
      await page.goto(`${BASE}/documents`);
      await page.getByText("Rechnung NovaNet September").first().waitFor();
      await page.mouse.move(700, 500);
      await pause(page, 500);
      await start();
      await pause(page, 600);
      await glideClick(page, page.getByText("Jahresabrechnung Strom 2025").first());
      await pause(page, 2500);
      await glideClick(page, page.getByText("Stromvertrag Ökostrom Plus").first());
      await pause(page, 2500);
      await glideClick(page, page.getByText("Glasfaser-Vertrag NovaNet 1000").first());
      await pause(page, 2500);
    }),
};

await mkdir(OUT, { recursive: true });
const browser = await chromium.launch({ executablePath: "/opt/pw-browsers/chromium" });
const wanted = process.argv.slice(2);
for (const [name, run] of Object.entries(SCENES)) {
  if (wanted.length && !wanted.includes(name)) continue;
  try {
    await run(browser);
  } catch (error) {
    console.log(name, "failed:", error.message.split("\n")[0]);
  }
}
await browser.close();
