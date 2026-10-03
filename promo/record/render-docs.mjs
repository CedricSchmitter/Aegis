// Renders the fictional household letters as page images (out/pages/<id>-<page>.png) and the photo of the letter
// that is uploaded in the video (out/pages/upload-photo.jpg).
import { chromium } from "playwright";
import { mkdir, readFile } from "node:fs/promises";

const out = new URL("../out/pages/", import.meta.url).pathname;
await mkdir(out, { recursive: true });

const letter = ({ sender, color, logo, subject, date, ref, body, table }) => `
<html><head><style>
  body { margin:0; width:1240px; height:1754px; background:#fff; font-family: "DejaVu Sans", Arial, sans-serif; color:#222; }
  .page { padding: 110px 120px; }
  .head { display:flex; justify-content:space-between; align-items:center; border-bottom: 6px solid ${color}; padding-bottom: 30px; }
  .logo { font-size: 46px; font-weight: 800; color:${color}; letter-spacing: 1px; }
  .logo small { display:block; font-size: 20px; color:#666; font-weight: 400; letter-spacing: 0; }
  .mark { width: 90px; height: 90px; border-radius: 18px; background:${color}; color:#fff; font-size:48px; font-weight:800; display:grid; place-items:center; }
  .addr { margin-top: 80px; font-size: 22px; line-height: 1.5; color:#333; }
  .addr .small { font-size: 15px; color:#888; text-decoration: underline; }
  .meta { text-align:right; font-size: 20px; color:#555; margin-top: -120px; line-height:1.6; }
  h1 { font-size: 32px; margin: 90px 0 36px; }
  p { font-size: 23px; line-height: 1.65; margin: 0 0 22px; }
  table { width:100%; border-collapse: collapse; font-size: 22px; margin: 20px 0 30px; }
  td { padding: 14px 10px; border-bottom: 1px solid #ddd; }
  td:last-child { text-align:right; font-weight:600; }
  tr.total td { border-top: 3px solid #222; font-weight: 800; }
  .hl { background: #fff3a8; padding: 2px 4px; }
  .foot { position:absolute; bottom: 70px; left:120px; right:120px; font-size: 15px; color:#999; border-top:1px solid #ddd; padding-top: 16px; }
</style></head><body><div class="page">
  <div class="head"><div class="logo">${sender}<small>${logo}</small></div><div class="mark">${sender[0]}</div></div>
  <div class="addr"><div class="small">${sender} · Postfach 41 20 · 04177 Lindenau</div>Mara und Jonas Berger<br>Lindenweg 12<br>04179 Lindenau</div>
  <div class="meta">Lindenau, ${date}<br>${ref}</div>
  <h1>${subject}</h1>
  ${body.map((line) => `<p>${line}</p>`).join("")}
  ${table ? `<table>${table.map(([a, b], i) => `<tr class="${i === table.length - 1 ? "total" : ""}"><td>${a}</td><td>${b}</td></tr>`).join("")}</table>` : ""}
  <div class="foot">${sender} · Sitz Lindenau · Fiktives Beispieldokument für eine Produktdemo</div>
</div></body></html>`;

const lorem = "Bei Fragen erreichen Sie uns werktags von 8 bis 18 Uhr. Bitte geben Sie bei Rückfragen immer Ihre Kunden- bzw. Vertragsnummer an.";

const pages = {
  "d-strom-vertrag-0": letter({ sender: "Stadtwerke Lindenau", color: "#0a7d5a", logo: "Energie für die Region", subject: "Ihr Stromliefervertrag „Ökostrom Plus“", date: "12. August 2025", ref: "Vertrag 7781-204", body: ["Sehr geehrte Frau Berger, sehr geehrter Herr Berger,", "vielen Dank für Ihr Vertrauen. Hiermit bestätigen wir Ihren Stromliefervertrag zu folgenden Konditionen:"], table: [["Tarif", "Ökostrom Plus (100 % Wasserkraft)"], ["Arbeitspreis", "31,80 ct/kWh"], ["Grundpreis", "11,90 € / Monat"], ["Abschlag", "94,00 € / Monat"]] }),
  "d-strom-vertrag-1": letter({ sender: "Stadtwerke Lindenau", color: "#0a7d5a", logo: "Energie für die Region", subject: "Laufzeit und Kündigung", date: "12. August 2025", ref: "Vertrag 7781-204 · Seite 2", body: ["Die Erstlaufzeit beträgt 15 Monate ab Lieferbeginn.", '<span class="hl">Kündigungsfrist: 1 Monat zum Ende der Laufzeit. Ohne Kündigung verlängert sich der Vertrag um weitere 12 Monate.</span>', "Preisanpassungen teilen wir Ihnen mindestens einen Monat im Voraus mit; Sie haben dann ein Sonderkündigungsrecht.", lorem] }),
  "d-strom-abrechnung-0": letter({ sender: "Stadtwerke Lindenau", color: "#0a7d5a", logo: "Energie für die Region", subject: "Jahresabrechnung Strom 2025", date: "25. August 2026", ref: "Kundennr. 55-120-994", body: ["Sehr geehrte Familie Berger,", "anbei erhalten Sie Ihre Jahresabrechnung für den Zeitraum 01.08.2025 bis 31.07.2026."], table: [["Verbrauch", "3.412 kWh"], ["Arbeitspreis", "31,80 ct/kWh"], ["Grundpreis (12 Monate)", "142,80 €"], ["Neuer Abschlag", "94,00 € / Monat"]] }),
  "d-novanet-0": letter({ sender: "NovaNet Glasfaser", color: "#6a2bd9", logo: "Licht. Geschwindigkeit.", subject: "Auftragsbestätigung NovaNet 1000", date: "26. Februar 2026", ref: "Kundennr. NN-448812", body: ["Hallo Mara, hallo Jonas,", "willkommen im Glasfasernetz! Ihr Anschluss mit bis zu 1.000 Mbit/s ist ab dem 15. März 2026 aktiv.", "Mindestvertragslaufzeit 24 Monate, danach monatlich kündbar mit einer Frist von 3 Monaten."], table: [["NovaNet 1000", "49,99 € / Monat"], ["Router Nova Box 7", "0,00 €"], ["Gesamt monatlich", "49,99 €"]] }),
  "d-novanet-rechnung-0": letter({ sender: "NovaNet Glasfaser", color: "#6a2bd9", logo: "Licht. Geschwindigkeit.", subject: "Rechnung September 2026", date: "29. September 2026", ref: "Rechnung R-2026-09-448812", body: ["Hallo Mara, hallo Jonas,", "hier ist Ihre Rechnung für September."], table: [["NovaNet 1000", "49,99 €"], ["Gesamt", "49,99 €"]] }),
  "d-irongrid-0": letter({ sender: "Iron Grid Fitness", color: "#d81b60", logo: "Train in the grid", subject: "Willkommen bei Iron Grid", date: "18. Oktober 2025", ref: "Mitgliedsnummer: IG-20417", body: ["Hey Mara,", "deine Mitgliedschaft „Grid Unlimited“ startet am 09.11.2025.", "Die Mindestlaufzeit beträgt 12 Monate. Kündigungsfrist: 1 Monat zum Ende der Laufzeit, sonst verlängert sie sich automatisch um weitere 12 Monate."], table: [["Grid Unlimited", "39,90 € / Monat"], ["Startpaket", "19,00 €"], ["Monatlich", "39,90 €"]] }),
  "d-kfz-police-0": letter({ sender: "Vulkan Versicherung", color: "#e65100", logo: "Sicher unterwegs", subject: "Ihr Versicherungsschein Kfz", date: "28. Dezember 2025", ref: "Police KV-3381-77", body: ["Sehr geehrter Herr Berger,", "wir freuen uns, Ihr Fahrzeug LI-JB 2077 zu versichern."], table: [["Haftpflicht", "318,20 €"], ["Vollkasko (SB 300 €)", "294,20 €"], ["Jahresbeitrag", "612,40 €"]] }),
  "d-vulkan-neu-0": letter({ sender: "Vulkan Versicherung", color: "#e65100", logo: "Sicher unterwegs", subject: "Beitragsanpassung zum 01.01.2027", date: "1. Oktober 2026", ref: "Police KV-3381-77", body: ["Sehr geehrter Herr Berger,", "aufgrund gestiegener Werkstatt- und Ersatzteilkosten passen wir den Beitrag Ihrer Kfz-Versicherung zum 01.01.2027 an.", "Sie haben ein Sonderkündigungsrecht bis einen Monat nach Zugang dieses Schreibens."], table: [["Bisheriger Jahresbeitrag", "612,40 €"], ["Neuer Jahresbeitrag", "668,90 €"]] }),
  "d-steuer-0": letter({ sender: "Finanzamt Lindenau", color: "#37474f", logo: "Steuernummer 231/442/18800", subject: "Bescheid für 2024 über Einkommensteuer", date: "30. Juni 2026", ref: "Steuernr. 231/442/18800", body: ["Festsetzung", "Die Einkommensteuer wird festgesetzt auf den unten genannten Betrag."], table: [["Festgesetzt", "14.208,00 €"], ["Bereits gezahlt", "15.390,00 €"], ["Erstattung", "1.182,00 €"]] }),
  "d-heizung-0": letter({ sender: "Kälte & Klima Brandt", color: "#0277bd", logo: "Meisterbetrieb seit 1998", subject: "Angebot Jahreswartung Wärmepumpe", date: "21. September 2026", ref: "Angebot A-2026-311", body: ["Sehr geehrte Familie Berger,", "gern führen wir die jährliche Wartung Ihrer Luft-Wasser-Wärmepumpe durch. Terminvorschlag: 05.10.2026, 9 Uhr."], table: [["Wartungspauschale", "189,00 €"], ["Anfahrt", "29,00 €"], ["Gesamt", "218,00 €"]] }),
  "d-nebenkosten-0": letter({ sender: "Stadt Lindenau", color: "#455a64", logo: "Steueramt", subject: "Grundsteuerbescheid 2026", date: "6. Mai 2026", ref: "Aktenzeichen GS-12-0441", body: ["Für das Grundstück Lindenweg 12 wird die Grundsteuer festgesetzt."], table: [["Jahresbetrag", "486,00 €"], ["Fällig je Quartal", "121,50 €"]] }),
  "d-zahnarzt-0": letter({ sender: "Zahnarztpraxis Dr. Okafor", color: "#00897b", logo: "Lindenau Mitte", subject: "Heil- und Kostenplan", date: "12. September 2026", ref: "Patientin: Mara Berger", body: ["Bitte reichen Sie diesen Plan vor Behandlungsbeginn bei Ihrer Krankenkasse ein."], table: [["Kassenanteil (geschätzt)", "412,00 €"], ["Eigenanteil (geschätzt)", "688,00 €"]] }),
};

const browser = await chromium.launch({ executablePath: "/opt/pw-browsers/chromium" });
const page = await browser.newPage({ viewport: { width: 1240, height: 1754 } });
for (const [name, html] of Object.entries(pages)) {
  await page.setContent(html);
  await page.screenshot({ path: `${out}${name}.png` });
}

// A phone photo of the new letter: on a dark desk, slightly rotated, under a cyan desk lamp.
const photo = await browser.newPage({ viewport: { width: 1500, height: 2000 } });
await photo.setContent(`<html><body style="margin:0;width:1500px;height:2000px;overflow:hidden;
  background: radial-gradient(circle at 70% 20%, #1b3a4a 0%, #0d1418 45%, #07090b 100%);">
  <img src="data:image/png;base64,${(await readFile(`${out}d-vulkan-neu-0.png`)).toString("base64")}" style="position:absolute;left:170px;top:170px;width:1140px;
    transform: rotate(-3.2deg) perspective(2000px) rotateX(4deg);
    box-shadow: 0 40px 80px rgba(0,0,0,.7); filter: brightness(.94) contrast(1.05);">
  <div style="position:absolute;inset:0;background:linear-gradient(160deg,rgba(0,229,255,.10),transparent 40%,rgba(0,0,0,.25))"></div>
</body></html>`);
await photo.screenshot({ path: `${out}upload-photo.jpg`, type: "jpeg", quality: 90 });
await browser.close();
console.log("rendered", Object.keys(pages).length, "pages");
