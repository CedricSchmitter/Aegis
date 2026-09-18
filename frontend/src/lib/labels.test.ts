import { describe, expect, it } from "vitest";
import type { LabelChipDto, LabelDto } from "../api/types";
import {
  activeLabels,
  byId,
  findByName,
  labelName,
  matchLabels,
  normalizeName,
  ownLabelIds,
  subjectFromKey,
  subjectKey,
  suggestions,
} from "./labels";

const t = ((key: string, options?: { defaultValue?: string }) => {
  const catalog: Record<string, string> = {
    "labels.builtin.invoice": "Rechnung",
    "labels.builtin.water": "Wasser",
  };
  return catalog[key] ?? options?.defaultValue ?? key;
}) as never;

function label(overrides: Partial<LabelDto>): LabelDto {
  return {
    id: overrides.id ?? "id",
    facet: overrides.facet ?? "ORGANIZATION",
    builtinKey: overrides.builtinKey ?? null,
    name: overrides.name ?? null,
    aliases: overrides.aliases ?? [],
    status: overrides.status ?? "ACTIVE",
    mergedInto: overrides.mergedInto ?? null,
    assignmentCount: overrides.assignmentCount ?? 0,
    suggestionCount: overrides.suggestionCount ?? 0,
    folderCount: overrides.folderCount ?? 0,
    version: 1,
  };
}

const invoice = label({ id: "invoice", facet: "DOCUMENT_TYPE", builtinKey: "invoice" });
const telekom = label({ id: "telekom", name: "Telekom", aliases: ["Deutsche Telekom"], assignmentCount: 5 });
const vodafone = label({ id: "vodafone", name: "Vodafone", assignmentCount: 9 });
const retired = label({ id: "retired", name: "Arcor", status: "RETIRED" });
const labels = [invoice, telekom, vodafone, retired];

describe("label presentation", () => {
  it("translates built-in labels and prefers a custom name", () => {
    expect(labelName(invoice, t)).toBe("Rechnung");
    expect(labelName(telekom, t)).toBe("Telekom");
    expect(labelName(label({ builtinKey: "unknownKey" }), t)).toBe("unknownKey");
    expect(labelName(undefined, t)).toBe("");
  });

  it("folds case, width and spacing when comparing names", () => {
    expect(normalizeName("  Deutsche   TELEKOM ")).toBe("deutsche telekom");
    expect(normalizeName("ＴＥＬＥＫＯＭ")).toBe("telekom");
  });

  it("finds labels by name, alias or translated built-in name", () => {
    expect(matchLabels(labels, "ORGANIZATION", "deutsche", t)).toEqual([telekom]);
    expect(matchLabels(labels, "DOCUMENT_TYPE", "rechn", t)).toEqual([invoice]);
    expect(matchLabels(labels, "ORGANIZATION", "", t)).toEqual([vodafone, telekom]);
    expect(matchLabels(labels, "ORGANIZATION", "arcor", t)).toEqual([]);
    expect(activeLabels(labels, "ORGANIZATION")).toEqual([telekom, vodafone]);
  });

  it("detects an existing name including retired entries so no duplicate is created", () => {
    expect(findByName(labels, "ORGANIZATION", " telekom ", t)?.id).toBe("telekom");
    expect(findByName(labels, "ORGANIZATION", "Deutsche Telekom", t)?.id).toBe("telekom");
    expect(findByName(labels, "ORGANIZATION", "Arcor", t)?.id).toBe("retired");
    expect(findByName(labels, "TOPIC", "Telekom", t)).toBeUndefined();
    expect(byId(labels).get("telekom")).toBe(telekom);
  });

  it("separates own effective labels from folder chips and suggestions", () => {
    const chips: LabelChipDto[] = [
      chip({ labelId: "telekom", source: "OWN", reviewState: "CONFIRMED" }),
      chip({ labelId: "invoice", source: "FOLDER", folderId: "f1" }),
      chip({ labelId: "water", source: "OWN", reviewState: "SUGGESTED", origin: "AUTOMATIC" }),
      chip({ labelId: "applied", source: "OWN", reviewState: "APPLIED", origin: "AUTOMATIC" }),
    ];

    expect(ownLabelIds(chips)).toEqual(["telekom", "applied"]);
    expect(suggestions(chips).map((entry) => entry.labelId)).toEqual(["water"]);
  });

  it("maps subject values to their stable keys", () => {
    expect(subjectKey({ kind: "HOUSE", memberId: null })).toBe("HOUSE");
    expect(subjectKey({ kind: "MEMBER", memberId: "m-1" })).toBe("MEMBER:m-1");
    expect(subjectFromKey("MEMBER:m-1")).toEqual({ kind: "MEMBER", memberId: "m-1" });
    expect(subjectFromKey("HOUSEHOLD")).toEqual({ kind: "HOUSEHOLD", memberId: null });
  });
});

function chip(overrides: Partial<LabelChipDto>): LabelChipDto {
  return {
    labelId: overrides.labelId ?? "id",
    facet: overrides.facet ?? "ORGANIZATION",
    builtinKey: overrides.builtinKey ?? null,
    name: overrides.name ?? null,
    source: overrides.source ?? "OWN",
    origin: overrides.origin ?? "MANUAL",
    reviewState: overrides.reviewState ?? "CONFIRMED",
    confidence: overrides.confidence ?? null,
    sourceRef: overrides.sourceRef ?? null,
    folderId: overrides.folderId ?? null,
  };
}
