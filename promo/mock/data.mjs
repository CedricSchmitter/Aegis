// Fictional demo household for the promo video. Nothing here comes from real household data.

const DAY = 86_400_000;
export const now = () => new Date();
export const iso = (date) => date.toISOString();
export const daysFromNow = (days) => new Date(Date.now() + days * DAY);
export const localDate = (days) => {
  const d = daysFromNow(days);
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, "0")}-${String(d.getDate()).padStart(2, "0")}`;
};

export const MARA = { id: "m-mara", username: "mara", displayName: "Mara", kind: "PERSON", locale: "DE" };
export const JONAS = { id: "m-jonas", username: "jonas", displayName: "Jonas", kind: "PERSON", locale: "DE" };
export const members = [MARA, JONAS];

export const categories = [
  { id: "c-haus", name: "Haus", parentId: null },
  { id: "c-nebenkosten", name: "Nebenkosten", parentId: "c-haus" },
  { id: "c-handwerker", name: "Handwerker", parentId: "c-haus" },
  { id: "c-vers", name: "Versicherungen", parentId: null },
  { id: "c-kfz", name: "KFZ", parentId: "c-vers" },
  { id: "c-vertraege", name: "Verträge", parentId: null },
  { id: "c-strom", name: "Strom", parentId: "c-vertraege" },
  { id: "c-internet", name: "Internet", parentId: "c-vertraege" },
  { id: "c-freizeit", name: "Freizeit", parentId: "c-vertraege" },
  { id: "c-finanzen", name: "Finanzen", parentId: null },
  { id: "c-steuer", name: "Steuer", parentId: "c-finanzen" },
  { id: "c-gesundheit", name: "Gesundheit", parentId: null },
];

export const labels = [
  ["l-rechnung", "DOCUMENT_TYPE", "Rechnung"],
  ["l-vertrag", "DOCUMENT_TYPE", "Vertrag"],
  ["l-bescheid", "DOCUMENT_TYPE", "Bescheid"],
  ["l-brief", "DOCUMENT_TYPE", "Brief"],
  ["l-police", "DOCUMENT_TYPE", "Police"],
  ["l-stadtwerke", "ORGANIZATION", "Stadtwerke Lindenau"],
  ["l-novanet", "ORGANIZATION", "NovaNet Glasfaser"],
  ["l-vulkan", "ORGANIZATION", "Vulkan Versicherung"],
  ["l-irongrid", "ORGANIZATION", "Iron Grid Fitness"],
  ["l-finanzamt", "ORGANIZATION", "Finanzamt Lindenau"],
  ["l-klima", "ORGANIZATION", "Kälte & Klima Brandt"],
  ["l-strom", "TOPIC", "Strom"],
  ["l-internet", "TOPIC", "Internet"],
  ["l-kfz", "TOPIC", "KFZ"],
  ["l-steuer", "TOPIC", "Steuer"],
  ["l-heizung", "TOPIC", "Heizung"],
  ["l-sport", "TOPIC", "Sport"],
  ["l-wichtig", "TAG", "Wichtig"],
].map(([id, facet, name]) => ({
  id,
  facet,
  name,
  aliases: [],
  assignmentCount: 3,
  folderCount: 0,
  suggestionCount: 0,
  builtinKey: null,
  mergedInto: null,
  status: "ACTIVE",
  version: 1,
}));

const labelById = Object.fromEntries(labels.map((label) => [label.id, label]));
const chip = (labelId, origin = "MANUAL") => ({
  labelId,
  facet: labelById[labelId].facet,
  name: labelById[labelId].name,
  builtinKey: null,
  confidence: origin === "AUTOMATIC" ? 0.94 : null,
  folderId: null,
  origin,
  reviewState: origin === "AUTOMATIC" ? "ACCEPTED" : null,
  source: origin === "AUTOMATIC" ? "AI" : "MEMBER",
  sourceRef: null,
});

export const pathOf = (categoryId) => {
  const path = [];
  let current = categories.find((c) => c.id === categoryId);
  while (current) {
    path.unshift({ id: current.id, name: current.name });
    current = categories.find((c) => c.id === current.parentId);
  }
  return path;
};

/** Each entry also names the page template the image renderer uses. */
export const documentSeeds = [
  { id: "d-strom-vertrag", title: "Stromvertrag Ökostrom Plus", folder: "c-strom", labels: ["l-vertrag", "l-stadtwerke", "l-strom", "l-wichtig"], days: -410, by: MARA, pages: 2 },
  { id: "d-strom-abrechnung", title: "Jahresabrechnung Strom 2025", folder: "c-strom", labels: ["l-rechnung", "l-stadtwerke", "l-strom"], days: -38, by: JONAS, pages: 1 },
  { id: "d-novanet", title: "Glasfaser-Vertrag NovaNet 1000", folder: "c-internet", labels: ["l-vertrag", "l-novanet", "l-internet"], days: -220, by: JONAS, pages: 1 },
  { id: "d-irongrid", title: "Mitgliedschaft Iron Grid Fitness", folder: "c-freizeit", labels: ["l-vertrag", "l-irongrid", "l-sport"], days: -350, by: MARA, pages: 1 },
  { id: "d-kfz-police", title: "Kfz-Police Vulkan 2026", folder: "c-kfz", labels: ["l-police", "l-vulkan", "l-kfz"], days: -280, by: JONAS, pages: 1 },
  { id: "d-steuer", title: "Steuerbescheid 2024", folder: "c-steuer", labels: ["l-bescheid", "l-finanzamt", "l-steuer"], days: -95, by: MARA, pages: 1 },
  { id: "d-heizung", title: "Wartungsangebot Wärmepumpe", folder: "c-handwerker", labels: ["l-brief", "l-klima", "l-heizung"], days: -12, by: MARA, pages: 1 },
  { id: "d-nebenkosten", title: "Grundsteuerbescheid 2026", folder: "c-nebenkosten", labels: ["l-bescheid", "l-finanzamt"], days: -150, by: JONAS, pages: 1 },
  { id: "d-novanet-rechnung", title: "Rechnung NovaNet September", folder: "c-internet", labels: ["l-rechnung", "l-novanet", "l-internet"], days: -4, by: JONAS, pages: 1 },
  { id: "d-zahnarzt", title: "Heil- und Kostenplan Zahnarzt", folder: "c-gesundheit", labels: ["l-brief"], days: -21, by: MARA, pages: 1 },
];

/** The letter uploaded during the video; its analysis result is revealed over time. */
export const UPLOAD = {
  id: "d-vulkan-neu",
  title: "Beitragsanpassung Kfz-Versicherung 2027",
  rawTitle: "Scan Vulkan Brief",
  folder: "c-kfz",
  labels: ["l-brief", "l-vulkan", "l-kfz", "l-wichtig"],
};

export function documentDto(seed, overrides = {}) {
  const uploaded = seed.uploadedAt ?? iso(daysFromNow(seed.days ?? 0));
  return {
    id: seed.id,
    title: seed.title,
    titleOrigin: "MEMBER",
    originalFilename: `${seed.id}.pdf`,
    filename: { filename: `${seed.title}.pdf`, override: null, source: "GENERATED" },
    contentType: "application/pdf",
    sizeBytes: 182_000,
    checksumSha256: "0".repeat(64),
    uploadedAt: uploaded,
    uploadedBy: (seed.by ?? MARA).id,
    version: 3,
    archivePdfAvailable: true,
    originalAvailable: true,
    subjects: [{ kind: "HOUSE", memberId: null }],
    textRecognition: { state: "RECOGNIZED", recognizedCharacters: 2400, checkedAt: uploaded, nextAttemptAt: null },
    processing: {
      state: "ARCHIVED",
      attempts: 1,
      archivedAt: uploaded,
      paperlessDocumentId: 100,
      retryScheduled: false,
      lastErrorAt: null,
      lastErrorCode: null,
      lastErrorMessage: null,
      nextAttemptAt: null,
    },
    analysis: {
      state: "CLASSIFIED",
      candidates: [],
      subjects: [],
      flags: [],
      history: [],
      generation: 1,
      version: 1,
      documentDateStatus: "FOUND",
      documentDate: uploaded.slice(0, 10),
      documentDateEvidence: null,
      title: null,
      titleDecision: null,
      shortTopic: null,
      model: "claude-sonnet",
      completedAt: uploaded,
      requestedAt: uploaded,
      requestedBy: (seed.by ?? MARA).id,
      runId: null,
      runState: null,
      trigger: "UPLOAD",
      failureCategory: null,
      failureCode: null,
      nextAttemptAt: null,
      correspondentLabelId: null,
    },
    classification: {
      categoryId: seed.folder,
      path: pathOf(seed.folder),
      folderSubjects: [],
      labels: (seed.labels ?? []).map((id) => chip(id, seed.autoLabels ? "AUTOMATIC" : "MANUAL")),
      placement: { origin: seed.autoPlaced ? "AUTOMATIC" : "MANUAL", byMember: !seed.autoPlaced, revertible: !!seed.autoPlaced, at: uploaded, by: null, confidence: seed.autoPlaced ? 0.96 : null, sourceRef: null },
      placementSuggestion: null,
      version: 2,
    },
    ...overrides,
  };
}

export const contracts = [
  {
    id: "k-irongrid",
    name: "Iron Grid Fitness",
    counterparty: "Iron Grid Fitness GmbH",
    deadlineDays: 6,
    endDays: 37,
    doc: "d-irongrid",
    amount: "39.90 EUR",
  },
  {
    id: "k-strom",
    name: "Ökostrom Plus",
    counterparty: "Stadtwerke Lindenau",
    deadlineDays: 27,
    endDays: 58,
    doc: "d-strom-vertrag",
    amount: "94.00 EUR",
  },
  {
    id: "k-novanet",
    name: "Glasfaser NovaNet 1000",
    counterparty: "NovaNet Glasfaser",
    deadlineDays: 74,
    endDays: 164,
    doc: "d-novanet",
    amount: "49.99 EUR",
  },
  {
    id: "k-kfz",
    name: "Kfz-Versicherung",
    counterparty: "Vulkan Versicherung",
    deadlineDays: 59,
    endDays: 89,
    doc: "d-kfz-police",
    amount: "612.40 EUR",
  },
];

export const tasks = [
  { id: "t-fitness", title: "Fitnessstudio kündigen", due: 6, owner: MARA, contract: "k-irongrid", note: "Einschreiben, Mitgliedsnummer IG-20417" },
  { id: "t-heizung", title: "Wartungstermin Wärmepumpe bestätigen", due: 2, planned: 0, owner: JONAS, doc: "d-heizung", note: "" },
  { id: "t-steuer", title: "Belege Steuererklärung 2025 sammeln", due: -1, owner: MARA, note: "" },
  { id: "t-strom", title: "Stromtarife vergleichen", due: 20, owner: null, contract: "k-strom", note: "" },
  { id: "t-zahnarzt", title: "Kostenplan bei Krankenkasse einreichen", due: 9, owner: JONAS, doc: "d-zahnarzt", note: "" },
  { id: "t-kfz", title: "Neuen Kfz-Beitrag prüfen", due: 25, owner: null, doc: UPLOAD.id, note: "", hidden: true },
];
