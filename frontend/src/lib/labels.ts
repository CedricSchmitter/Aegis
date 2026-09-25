import type { TFunction } from "i18next";
import type { Facet, LabelChipDto, LabelDto, SubjectDto } from "../api/types";

/** Display name: a member-authored name wins, a built-in label is translated by its stable key. */
export function labelName(label: LabelDto | LabelChipDto | undefined, t: TFunction): string {
  if (!label) return "";
  if (label.name) return label.name;
  if (label.builtinKey) return t(`labels.builtin.${label.builtinKey}`, { defaultValue: label.builtinKey });
  return "";
}

/** Case, width and spacing insensitive comparison key, mirroring the backend match key. */
export function normalizeName(text: string): string {
  return text.normalize("NFKC").trim().replace(/\s+/g, " ").toLocaleLowerCase();
}

export function byId(labels: LabelDto[]): Map<string, LabelDto> {
  return new Map(labels.map((label) => [label.id, label]));
}

export function activeLabels(labels: LabelDto[], facet: Facet): LabelDto[] {
  return labels.filter((label) => label.facet === facet && label.status === "ACTIVE");
}

/**
 * Autocomplete over names, aliases and the translated built-in names. An empty query lists the most used
 * labels first so that the common values are one tap away.
 */
export function matchLabels(labels: LabelDto[], facet: Facet, query: string, t: TFunction): LabelDto[] {
  const needle = normalizeName(query);
  const candidates = activeLabels(labels, facet).filter((label) => {
    if (!needle) return true;
    const haystack = [labelName(label, t), label.name ?? "", ...label.aliases].map(normalizeName);
    return haystack.some((value) => value.includes(needle));
  });
  return candidates.sort((a, b) => {
    const nameA = normalizeName(labelName(a, t));
    const nameB = normalizeName(labelName(b, t));
    if (needle) {
      const startsA = nameA.startsWith(needle) ? 0 : 1;
      const startsB = nameB.startsWith(needle) ? 0 : 1;
      if (startsA !== startsB) return startsA - startsB;
    } else if (a.assignmentCount !== b.assignmentCount) {
      return b.assignmentCount - a.assignmentCount;
    }
    return nameA.localeCompare(nameB);
  });
}

/** Whether a name is already taken in this facet (then the existing label is offered instead of a new one). */
export function findByName(labels: LabelDto[], facet: Facet, name: string, t: TFunction): LabelDto | undefined {
  const needle = normalizeName(name);
  if (!needle) return undefined;
  return labels.find(
    (label) =>
      label.facet === facet &&
      label.status !== "MERGED" &&
      [labelName(label, t), label.name ?? "", ...label.aliases].map(normalizeName).includes(needle),
  );
}

/** Stable key of a subject value, as used by the search API and the facet counts. */
export function subjectKey(subject: SubjectDto): string {
  return subject.kind === "MEMBER" && subject.memberId ? `MEMBER:${subject.memberId}` : subject.kind;
}

export function subjectFromKey(key: string): SubjectDto {
  return key.startsWith("MEMBER:")
    ? { kind: "MEMBER", memberId: key.substring("MEMBER:".length) }
    : { kind: key, memberId: null };
}

/**
 * Subject values a member can tick: the house and each person. A value the record already carries stays offered so
 * that it can be removed, even when it is no person (for example the assistant account).
 */
export function subjectChoices(persons: { id: string }[], saved: string[] = []): string[] {
  const choices = ["HOUSE", ...persons.map((person) => `MEMBER:${person.id}`)];
  return [...choices, ...saved.filter((key) => !choices.includes(key))];
}

/** Own effective labels of a document, used as the starting point when editing. */
export function ownLabelIds(chips: LabelChipDto[]): string[] {
  return chips
    .filter((chip) => chip.source === "OWN" && (chip.reviewState === "CONFIRMED" || chip.reviewState === "APPLIED"))
    .map((chip) => chip.labelId);
}

export function suggestions(chips: LabelChipDto[]): LabelChipDto[] {
  return chips.filter((chip) => chip.reviewState === "SUGGESTED");
}
