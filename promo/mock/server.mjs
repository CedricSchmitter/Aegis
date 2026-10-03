// Mocked Aegis API for the promo recordings. Serves the built frontend (frontend/dist) and answers /api with the
// fictional household from data.mjs. Uploads and chat questions advance through their states on a timer, so the
// real UI shows processing, classification and an answering agent.
import { createServer } from "node:http";
import { readFile, stat } from "node:fs/promises";
import { extname, join, normalize } from "node:path";
import { fileURLToPath } from "node:url";
import { randomUUID } from "node:crypto";
import * as D from "./data.mjs";

const here = fileURLToPath(new URL(".", import.meta.url));
const DIST = join(here, "../../frontend/dist");
const PAGES = join(here, "../out/pages");
const PORT = Number(process.env.PORT ?? 4173);

const json = (res, status, body) => {
  res.writeHead(status, { "Content-Type": "application/json" });
  res.end(JSON.stringify(body));
};
const readBody = (req) =>
  new Promise((resolve) => {
    const chunks = [];
    req.on("data", (chunk) => chunks.push(chunk));
    req.on("end", () => resolve(Buffer.concat(chunks)));
  });

// ---------------------------------------------------------------- state

let state;
// Recordings run the page in slow motion; uploads and answers advance at the same slowed pace.
let slow = 1;
function reset() {
  state = {
    me: D.MARA,
    uploads: [], // { id, at }
    conversations: new Map(),
  };
}
reset();

const elapsed = (at) => (Date.now() - at) / slow;

function uploadedDocument({ id, at }) {
  const t = elapsed(at);
  const seed = {
    id,
    title: D.UPLOAD.rawTitle,
    folder: null,
    labels: [],
    uploadedAt: new Date(at).toISOString(),
    by: state.me,
  };
  const doc = D.documentDto(seed);
  doc.classification.path = [];
  doc.classification.categoryId = null;
  doc.classification.placement = { origin: null, byMember: false, revertible: false, at: null, by: null, confidence: null, sourceRef: null };
  doc.contentType = "image/jpeg";
  if (t < 2500) {
    doc.processing.state = "RECEIVED";
    doc.analysis.state = "WAITING_FOR_ARCHIVE";
    doc.textRecognition.state = "NOT_ARCHIVED";
    doc.archivePdfAvailable = false;
  } else if (t < 5000) {
    doc.processing.state = "SUBMITTED";
    doc.analysis.state = "WAITING_FOR_ARCHIVE";
    doc.textRecognition.state = "NOT_ARCHIVED";
    doc.archivePdfAvailable = false;
  } else if (t < 9000) {
    doc.analysis.state = "PENDING";
    doc.analysis.runState = "RUNNING";
    // Keeps the list refreshing while the classification runs.
    doc.textRecognition.state = "PENDING";
    doc.processing.archivedAt = new Date(at + 5000).toISOString();
  } else {
    const done = D.documentDto({ ...seed, title: D.UPLOAD.title, folder: D.UPLOAD.folder, labels: D.UPLOAD.labels, autoLabels: true, autoPlaced: true });
    done.contentType = "image/jpeg";
    done.titleOrigin = "AI";
    done.analysis.title = D.UPLOAD.title;
    done.analysis.titleDecision = "APPLIED";
    done.analysis.shortTopic = "Neuer Kfz-Beitrag ab 01.01.2027";
    done.analysis.documentDate = D.localDate(-2);
    done.analysis.documentDateEvidence = "Lindenau, den …";
    return done;
  }
  return doc;
}

function allDocuments() {
  const seeds = D.documentSeeds.map((seed) => D.documentDto(seed));
  const uploaded = state.uploads.map(uploadedDocument);
  return [...uploaded.reverse(), ...seeds].sort((a, b) => b.uploadedAt.localeCompare(a.uploadedAt));
}

function categoryList() {
  const docs = allDocuments();
  const count = (id) => docs.filter((doc) => doc.classification.categoryId === id).length;
  return {
    categories: D.categories.map((c) => ({ ...c, documentCount: count(c.id), impliedLabelIds: [], impliedSubjects: [], version: 1 })),
    totalDocuments: docs.length,
    unsortedCount: docs.filter((doc) => !doc.classification.categoryId).length,
  };
}

function taskDto(task) {
  const contract = task.contract && D.contracts.find((c) => c.id === task.contract);
  const doc = task.doc && D.documentSeeds.find((d) => d.id === task.doc);
  return {
    id: task.id,
    title: task.title,
    note: task.note ?? "",
    status: "OPEN",
    dueDate: task.due == null ? null : D.localDate(task.due),
    plannedDate: task.planned == null ? null : D.localDate(task.planned),
    ownerId: task.owner?.id ?? null,
    createdAt: D.iso(D.daysFromNow(-20)),
    createdBy: D.MARA.id,
    updatedAt: D.iso(D.daysFromNow(-2)),
    completedAt: null,
    completedBy: null,
    version: 2,
    source: doc ? { documentId: doc.id, documentTitle: doc.title, documentDeleted: false, documentTrashed: false } : undefined,
    cancellationReview: contract
      ? {
          contractId: contract.id,
          contractName: contract.name,
          contractVersion: 3,
          contractDeleted: false,
          cancellationDeadline: D.localDate(contract.deadlineDays),
          contractEnd: D.localDate(contract.endDays),
          reminderDate: D.localDate(contract.deadlineDays - 14),
        }
      : undefined,
  };
}
const visibleTasks = () => D.tasks.filter((task) => !task.hidden).map(taskDto);

function deadlineDto(contract) {
  return {
    determined: true,
    deadline: D.localDate(contract.deadlineDays),
    contractEnd: D.localDate(contract.endDays),
    basis: "NOTICE_PERIOD_BEFORE_END",
    basedOn: [`${contract.id}-notice`, `${contract.id}-end`],
    contradicting: [],
    missing: [],
  };
}

function contractSummary(contract) {
  return {
    id: contract.id,
    name: contract.name,
    counterparty: contract.counterparty,
    status: "ACTIVE",
    contractEnd: D.localDate(contract.endDays),
    deadline: deadlineDto(contract),
    conflicts: 0,
    pendingFacts: 0,
    updatedAt: D.iso(D.daysFromNow(-3)),
    version: 3,
  };
}

function contractDto(contract) {
  const doc = D.documentSeeds.find((d) => d.id === contract.doc);
  const source = (page, quote) => ({ documentId: doc.id, documentTitle: doc.title, documentDeleted: false, documentTrashed: false, page, quote });
  const fact = (suffix, type, valueKind, value, page, quote, reviewState = "CONFIRMED") => ({
    id: `${contract.id}-${suffix}`,
    type,
    valueKind,
    value,
    note: "",
    origin: "AUTOMATIC",
    reviewState,
    effective: true,
    confidence: 0.97,
    observedAt: D.iso(D.daysFromNow(-60)),
    observedBy: null,
    decidedAt: D.iso(D.daysFromNow(-59)),
    decidedBy: D.MARA.id,
    validFrom: null,
    validUntil: null,
    corrects: null,
    supersededBy: null,
    runRef: null,
    source: source(page, quote),
  });
  return {
    ...contractSummary(contract),
    createdAt: D.iso(D.daysFromNow(-300)),
    createdBy: D.MARA.id,
    evaluatedOn: D.localDate(0),
    conflicts: [],
    sources: [source(1, null)],
    facts: [
      fact("party", "COUNTERPARTY", "TEXT", contract.counterparty, 1, null),
      fact("number", "CONTRACT_NUMBER", "TEXT", "IG-20417", 1, "Mitgliedsnummer: IG-20417"),
      fact("start", "START_DATE", "DATE", D.localDate(contract.endDays - 365), 1, null),
      fact("end", "END_DATE", "DATE", D.localDate(contract.endDays), 1, "Die Mindestlaufzeit beträgt 12 Monate."),
      fact("notice", "NOTICE_PERIOD", "PERIOD", "P1M", 2, "Kündigungsfrist: 1 Monat zum Ende der Laufzeit."),
      fact("renewal", "AUTOMATIC_RENEWAL", "FLAG", "true", 2, "verlängert sich automatisch um weitere 12 Monate"),
      fact("amount", "AMOUNT", "MONEY", contract.amount, 1, null),
    ],
  };
}

// ---------------------------------------------------------------- chat

const SCRIPTS = [
  {
    match: /strom|kündig|frist/i,
    progress: [
      [0, "ATTEMPT"],
      [600, "MODEL"],
      [1200, "TOOL", "household_catalog"],
      [2200, "TOOL", "list_contracts"],
      [3300, "TOOL", "search_documents"],
      [4300, "TOOL", "read_document"],
      [5400, "TOOL", "read_contract"],
      [6400, "MODEL"],
    ],
    answerAt: 7200,
    answer: {
      text:
        "Euer Stromvertrag „Ökostrom Plus“ bei den Stadtwerken Lindenau läuft bis " +
        "{end}. Die Kündigungsfrist beträgt einen Monat zum Laufzeitende [1], " +
        "ihr müsst also spätestens am {deadline} kündigen, sonst verlängert er sich automatisch um 12 Monate [1]. " +
        "Aktuell zahlt ihr 94 € Abschlag im Monat; laut Jahresabrechnung 2025 waren das 3.412 kWh bei 31,8 ct/kWh [2]. " +
        "Für den Tarifvergleich gibt es schon eine offene Aufgabe [3].",
      citations: [
        { number: 1, kind: "DOCUMENT", sourceId: "d-strom-vertrag", title: "Stromvertrag Ökostrom Plus", page: 2, quote: "Kündigungsfrist: 1 Monat zum Ende der Laufzeit. Ohne Kündigung verlängert sich der Vertrag um weitere 12 Monate.", check: "VERIFIED" },
        { number: 2, kind: "DOCUMENT", sourceId: "d-strom-abrechnung", title: "Jahresabrechnung Strom 2025", page: 1, quote: "Verbrauch 3.412 kWh · Arbeitspreis 31,80 ct/kWh · neuer Abschlag 94,00 €", check: "VERIFIED" },
        { number: 3, kind: "TASK", sourceId: "t-strom", title: "Stromtarife vergleichen", page: null, quote: null, check: "VERIFIED" },
      ],
      evidence: "COMPLETE",
      caveats: [],
    },
  },
  {
    match: /.*/,
    progress: [
      [0, "ATTEMPT"],
      [800, "TOOL", "household_catalog"],
      [2000, "TOOL", "list_tasks"],
    ],
    answerAt: 3500,
    answer: {
      text: "Diese Woche ist vor allem eins wichtig: Die Mitgliedschaft bei Iron Grid Fitness muss bis {fitness} gekündigt werden [1].",
      citations: [{ number: 1, kind: "TASK", sourceId: "t-fitness", title: "Fitnessstudio kündigen", page: null, quote: null, check: "VERIFIED" }],
      evidence: "COMPLETE",
      caveats: [],
    },
  },
];

const fmt = (days) => {
  const d = D.daysFromNow(days);
  return d.toLocaleDateString("de-DE", { day: "numeric", month: "long", year: "numeric" });
};

function turnDto(turn) {
  const t = elapsed(turn.at);
  const script = turn.script;
  const answered = t >= script.answerAt;
  const progress = script.progress
    .filter(([offset]) => t >= offset)
    .map(([offset, kind, tool]) => ({ at: new Date(turn.at + offset).toISOString(), kind, tool: tool ?? null }));
  const text = script.answer.text
    .replace("{end}", fmt(58))
    .replace("{deadline}", fmt(27))
    .replace("{fitness}", fmt(6));
  return {
    id: turn.id,
    seq: turn.seq,
    runId: turn.runId,
    question: turn.question,
    locale: "DE",
    family: turn.family ?? "sonnet",
    askedAt: new Date(turn.at).toISOString(),
    answeredAt: answered ? new Date(turn.at + script.answerAt).toISOString() : null,
    state: answered ? "ANSWERED" : t < 400 ? "WAITING" : "WORKING",
    progress,
    answer: answered ? { ...script.answer, text } : undefined,
    failureCategory: null,
    failureCode: null,
    nextAttemptAt: null,
  };
}

function conversationDto(conversation) {
  return {
    id: conversation.id,
    title: conversation.title,
    createdAt: new Date(conversation.at).toISOString(),
    updatedAt: new Date(conversation.turns.at(-1)?.at ?? conversation.at).toISOString(),
    turns: conversation.turns.map(turnDto),
  };
}

function ask(conversation, body) {
  conversation.turns.push({
    id: body.turnId ?? randomUUID(),
    seq: conversation.turns.length + 1,
    runId: randomUUID(),
    question: body.question,
    family: body.family,
    at: Date.now(),
    script: SCRIPTS.find((s) => s.match.test(body.question)),
  });
}

const seededConversations = [
  { id: "7f6d2a10-0000-4000-8000-000000000001", title: "Was kostet uns Internet im Jahr?", days: -2, questions: 2 },
  { id: "7f6d2a10-0000-4000-8000-000000000002", title: "Wann war die letzte Heizungswartung?", days: -6, questions: 1 },
  { id: "7f6d2a10-0000-4000-8000-000000000003", title: "Welche Versicherungen haben wir?", days: -15, questions: 3 },
];

// ---------------------------------------------------------------- routes

const ok = (body) => [200, body];
const routes = [
  ["GET", /^\/api\/auth\/session$/, () => ok({ member: state.me, appearance: { palette: "STREETKID", effects: true } })],
  ["POST", /^\/api\/auth\/login$/, () => ok({ member: state.me, appearance: { palette: "STREETKID", effects: true } })],
  ["GET", /^\/api\/members$/, () => ok(D.members)],
  ["GET", /^\/api\/system\/version$/, () => ok({ application: "aegis", commit: "c0ffee1", buildTime: D.iso(D.now()) })],
  ["GET", /^\/api\/status$/, () => ok(statusDto())],
  ["GET", /^\/api\/reviews\/open-count$/, () => ok({ open: 0 })],
  ["GET", /^\/api\/reviews$/, () => ok([])],
  ["GET", /^\/api\/notices\/unread-count$/, () => ok({ unread: 2 })],
  ["GET", /^\/api\/notices$/, () => ok([])],
  ["GET", /^\/api\/categories$/, () => ok(categoryList())],
  ["GET", /^\/api\/labels$/, () => ok(D.labels)],
  [
    "GET",
    /^\/api\/today$/,
    () => {
      const all = visibleTasks();
      const today = D.localDate(0);
      return ok({
        date: today,
        calendar: { connected: false, provider: null, readSupported: false, writeSupported: false },
        overdue: all.filter((t) => t.dueDate && t.dueDate < today),
        dueToday: all.filter((t) => t.dueDate === today),
        plannedToday: all.filter((t) => t.plannedDate === today),
        upcoming: all.filter((t) => t.dueDate && t.dueDate > today && t.plannedDate !== today && t.ownerId),
        unassigned: all.filter((t) => !t.ownerId),
      });
    },
  ],
  [
    "GET",
    /^\/api\/contracts\/deadlines$/,
    () =>
      ok(
        D.contracts.map((c) => ({
          contractId: c.id,
          contractVersion: 3,
          name: c.name,
          counterparty: c.counterparty,
          status: "ACTIVE",
          cancellationDeadline: D.localDate(c.deadlineDays),
          contractEnd: D.localDate(c.endDays),
          basis: "NOTICE_PERIOD_BEFORE_END",
          basedOnFactIds: [],
          sourceDocumentId: c.doc,
        })),
      ),
  ],
  ["GET", /^\/api\/contracts\/trash$/, () => ok([])],
  ["GET", /^\/api\/contracts$/, () => ok(D.contracts.map(contractSummary))],
  ["GET", /^\/api\/contracts\/([^/]+)$/, (m) => ok(contractDto(D.contracts.find((c) => c.id === m[1]) ?? D.contracts[0]))],
  ["GET", /^\/api\/tasks\/trash$/, () => ok([])],
  ["GET", /^\/api\/tasks$/, () => ok(visibleTasks())],
  ["GET", /^\/api\/tasks\/([^/]+)$/, (m) => ok(visibleTasks().find((t) => t.id === m[1]) ?? visibleTasks()[0])],
  ["GET", /^\/api\/documents\/trash$/, () => ok([])],
  [
    "GET",
    /^\/api\/documents$/,
    (_m, url) => {
      let items = allDocuments();
      const folder = url.searchParams.get("category");
      if (folder) items = items.filter((doc) => doc.classification.path.some((step) => step.id === folder));
      const q = url.searchParams.get("query");
      if (q) {
        // Titles, labels and a few words "from the text", like the archive's full-text search.
        const words = Object.fromEntries(D.documentSeeds.map((seed) => [seed.id, seed.text ?? ""]));
        const haystack = (doc) => [doc.title, words[doc.id] ?? "", ...doc.classification.labels.map((l) => l.name)].join(" ").toLowerCase();
        items = items.filter((doc) => haystack(doc).includes(q.toLowerCase()));
      }
      return ok({
        items,
        page: 0,
        size: 50,
        matchingDocuments: items.length,
        totalDocuments: allDocuments().length,
        notContentIndexed: 0,
        contentSearch: q ? "READY" : "NOT_APPLIED",
        facets: { values: {}, missing: {} },
      });
    },
  ],
  ["GET", /^\/api\/documents\/([^/]+)$/, (m) => ok(allDocuments().find((doc) => doc.id === m[1]) ?? allDocuments()[0])],
  [
    "GET",
    /^\/api\/documents\/([^/]+)\/pages$/,
    (m) => {
      const seed = D.documentSeeds.find((d) => d.id === m[1]);
      const pages = Array.from({ length: seed?.pages ?? 1 }, (_, index) => {
        const [width, height] = seed?.pageSizes?.[index] ?? [595, 842];
        return { index, width, height };
      });
      return ok({ documentId: m[1], pages, sourceKind: "PDF", warnings: [], refusal: null });
    },
  ],
  ["GET", /^\/api\/documents\/([^/]+)\/derivations$/, () => ok({ derived: [] })],
  ["GET", /^\/api\/push\/config$/, () => ok({ available: true, publicKey: "BPromoKey" })],
  ["GET", /^\/api\/push\/devices$/, () => ok([])],
  ["GET", /^\/api\/push\/preferences$/, () => ok({ categories: ["TASKS", "DEADLINES", "REVIEW"], showDetails: true, quietStart: "22:00", quietEnd: "07:00", version: 1 })],
  ["GET", /^\/api\/exports$/, () => ok([])],
  [
    "GET",
    /^\/api\/chat\/conversations$/,
    () =>
      ok({
        available: true,
        unavailableReason: null,
        families: ["opus", "sonnet", "haiku"],
        defaultFamily: "sonnet",
        conversations: [
          ...[...state.conversations.values()].map((c) => ({ id: c.id, title: c.title, questions: c.turns.length, createdAt: new Date(c.at).toISOString(), updatedAt: new Date(c.at).toISOString() })),
          ...seededConversations.map((c) => ({ id: c.id, title: c.title, questions: c.questions, createdAt: D.iso(D.daysFromNow(c.days)), updatedAt: D.iso(D.daysFromNow(c.days)) })),
        ],
      }),
  ],
  ["GET", /^\/api\/chat\/conversations\/search$/, () => ok([])],
  [
    "GET",
    /^\/api\/chat\/conversations\/([^/]+)$/,
    (m) => {
      const conversation = state.conversations.get(m[1]);
      return conversation ? ok(conversationDto(conversation)) : [404, { code: "notFound" }];
    },
  ],
  [
    "POST",
    /^\/api\/chat\/conversations$/,
    (_m, _url, body) => {
      const conversation = { id: body.conversationId, title: body.question.slice(0, 60), at: Date.now(), turns: [] };
      state.conversations.set(conversation.id, conversation);
      ask(conversation, body);
      return [201, conversationDto(conversation)];
    },
  ],
  [
    "POST",
    /^\/api\/chat\/conversations\/([^/]+)\/turns$/,
    (m, _url, body) => {
      const conversation = state.conversations.get(m[1]);
      ask(conversation, body);
      return [201, conversationDto(conversation)];
    },
  ],
  [
    "POST",
    /^\/api\/documents$/,
    () => {
      const upload = { id: D.UPLOAD.id, at: Date.now() };
      state.uploads = [upload];
      return ok({ document: uploadedDocument(upload), duplicate: false });
    },
  ],
  [
    "POST",
    /^\/api\/page-edits$/,
    () => {
      state.pageEdit = { id: "pe-1", at: Date.now() };
      return ok(pageEditDto());
    },
  ],
  ["GET", /^\/api\/page-edits\/([^/]+)$/, () => ok(pageEditDto())],
  ["PUT", /^\/api\/members\/me\/appearance$/, (_m, _url, body) => ok(body)],
  [
    "PUT",
    /^\/api\/members\/me\/locale$/,
    (_m, _url, body) => {
      state.me = { ...state.me, locale: body.locale };
      return ok(state.me);
    },
  ],
  ["POST", /^\/__promo\/reset$/, (_m, url) => ((slow = Number(url.searchParams.get("slow") ?? 1)), reset(), ok({}))],
];

function pageEditDto() {
  const done = elapsed(state.pageEdit.at) > 2500;
  return {
    id: state.pageEdit.id,
    state: done ? "COMPLETED" : "REQUESTED",
    requestedAt: new Date(state.pageEdit.at).toISOString(),
    requestedBy: state.me.id,
    finishedAt: done ? D.iso(D.now()) : null,
    failureCode: null,
    results: done
      ? [
          { output: 0, documentId: "d-strom-preis", title: "Preisanpassung Stadtwerke 2027", existing: false, trashed: false },
          { output: 1, documentId: "d-zahnarzt-rechnung", title: "Rechnung Zahnarzt 2026-1187", existing: false, trashed: false },
        ]
      : [],
  };
}

function statusDto() {
  const at = D.iso(D.daysFromNow(-0.01));
  const check = (area) => ({ area, state: "OK", reason: "ok", params: {}, lastSuccessAt: at });
  return {
    generatedAt: D.iso(D.now()),
    checks: ["DATABASE", "PAPERLESS", "AI", "MAIL", "PUSH"].map(check),
    jobs: [],
    documentProcessing: {},
    storage: {
      backup: { state: "OK", lastSuccessAt: D.iso(D.daysFromNow(-0.3)), lastAttemptAt: D.iso(D.daysFromNow(-0.3)), runningSince: null, failureCode: null },
      disks: [
        { name: "data", state: "OK", freeBytes: 412e9, totalBytes: 512e9, lowBelowBytes: 20e9, measuredAt: at },
        { name: "backup", state: "OK", freeBytes: 1.6e12, totalBytes: 2e12, lowBelowBytes: 50e9, measuredAt: at },
      ],
    },
  };
}

// ---------------------------------------------------------------- images and static files

async function pageImage(res, id, index) {
  const name = id === D.UPLOAD.id ? "d-vulkan-neu-0.png" : `${id}-${index}.png`;
  try {
    const data = await readFile(join(PAGES, name));
    res.writeHead(200, { "Content-Type": "image/png", "Cache-Control": "no-store" });
    res.end(data);
  } catch {
    res.writeHead(404);
    res.end();
  }
}

// Headless Chromium has no PDF viewer, so the embedded "PDF" is an HTML page that looks like one.
function pdfViewer(res, id) {
  const count = D.documentSeeds.find((d) => d.id === id)?.pages ?? 1;
  const imgs = Array.from({ length: count }, (_, i) => `<img src="/api/documents/${id}/pages/${i}/preview">`).join("");
  res.writeHead(200, { "Content-Type": "text/html" });
  res.end(`<html><body style="margin:0;background:#323639;display:flex;flex-direction:column;align-items:center;gap:12px;padding:16px 0">
    <style>img{width:min(92%,820px);box-shadow:0 4px 16px rgba(0,0,0,.5)}</style>${imgs}</body></html>`);
}

const TYPES = { ".html": "text/html", ".js": "text/javascript", ".css": "text/css", ".svg": "image/svg+xml", ".png": "image/png", ".woff2": "font/woff2", ".woff": "font/woff", ".json": "application/json", ".webmanifest": "application/manifest+json", ".ico": "image/x-icon", ".wasm": "application/wasm" };

async function serveStatic(res, pathname) {
  let file = normalize(join(DIST, pathname));
  if (!file.startsWith(DIST)) file = join(DIST, "index.html");
  try {
    if ((await stat(file)).isDirectory()) file = join(DIST, "index.html");
  } catch {
    file = join(DIST, "index.html");
  }
  res.writeHead(200, { "Content-Type": TYPES[extname(file)] ?? "application/octet-stream" });
  res.end(await readFile(file));
}

createServer(async (req, res) => {
  const url = new URL(req.url, "http://localhost");
  const raw = await readBody(req);
  res.setHeader("Set-Cookie", "XSRF-TOKEN=promo; Path=/");
  const thumb = /^\/api\/documents\/([^/]+)\/(?:thumbnail|pages\/(\d+)\/preview)$/.exec(url.pathname);
  if (thumb) return pageImage(res, thumb[1], thumb[2] ?? 0);
  const viewer = /^\/api\/documents\/([^/]+)\/(?:archive-pdf|original)$/.exec(url.pathname);
  if (viewer) return pdfViewer(res, viewer[1]);
  if (url.pathname.startsWith("/api/") || url.pathname.startsWith("/__promo/")) {
    for (const [method, pattern, handler] of routes) {
      const match = pattern.exec(url.pathname);
      if (match && method === req.method) {
        let body = {};
        if (req.headers["content-type"]?.includes("json")) body = JSON.parse(raw.toString() || "{}");
        const [status, payload] = handler(match, url, body);
        return json(res, status, payload);
      }
    }
    console.log("unmocked", req.method, url.pathname + url.search);
    return json(res, req.method === "GET" ? 404 : 200, req.method === "GET" ? { code: "notFound" } : {});
  }
  return serveStatic(res, url.pathname);
}).listen(PORT, () => console.log(`promo mock on http://localhost:${PORT}`));
