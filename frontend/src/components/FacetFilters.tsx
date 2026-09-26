import { useTranslation } from "react-i18next";
import { FACET_GROUPS, type FacetCounts, type FacetGroup, type LabelDto, type MemberDto } from "../api/types";
import { facetParam, selectedValues, showsFacetGroup, toggleValue, type CatalogFilters } from "../lib/catalogSearch";
import { byId, labelName, subjectFromKey } from "../lib/labels";
import { Chip } from "./ui";

/** The display name of a facet value: a label from the vocabulary or a subject. */
function useValueName(labels: LabelDto[], members: MemberDto[]) {
  const { t } = useTranslation();
  const known = byId(labels);
  const subjectName = (key: string) => {
    const subject = subjectFromKey(key);
    if (subject.kind === "MEMBER") {
      const member = members.find((entry) => entry.id === subject.memberId);
      return t("subjects.MEMBER", { name: member?.displayName ?? t("app.unknownMember") });
    }
    return t(`subjects.${subject.kind}`);
  };
  return (group: FacetGroup, key: string) => (group === "SUBJECT" ? subjectName(key) : labelName(known.get(key), t) || key);
}

/** Toggles one value of a group; a changed filter always starts again at the first page. */
function toggled(filters: CatalogFilters, group: FacetGroup, value: string): CatalogFilters {
  const next = { ...filters, page: 0 };
  if (group === "SUBJECT") next.subject = toggleValue(filters.subject, value);
  else next[facetParam(group)] = toggleValue(selectedValues(filters, group), value);
  return next;
}

function withoutMissing(filters: CatalogFilters, group: FacetGroup): CatalogFilters {
  return { ...filters, page: 0, missing: toggleValue(filters.missing, group) as FacetGroup[] };
}

interface Props {
  filters: CatalogFilters;
  facets: FacetCounts;
  labels: LabelDto[];
  members: MemberDto[];
  onChange: (filters: CatalogFilters) => void;
}

/**
 * Facet filters with result counts. Several values inside a group match any of them (topics and tags can switch
 * to all), different groups are combined with AND, and a group offers an explicit "not set" filter next to its
 * values. A group without values is left out.
 */
export function FacetFilters({ filters, facets, labels, members, onChange }: Props) {
  const { t } = useTranslation();
  const valueName = useValueName(labels, members);

  // The assistant account is never what a document is about, so the subject filter offers only persons (ADR-0017).
  const isAssistant = (key: string) => {
    const subject = subjectFromKey(key);
    return subject.kind === "MEMBER" && members.find((entry) => entry.id === subject.memberId)?.kind === "ASSISTANT";
  };

  return (
    <div className="flex flex-col gap-4" data-testid="facet-filters">
      <p className="text-sm text-text-3">{t("documents.filterLogic")}</p>
      {FACET_GROUPS.map((group) => {
        const counts = facets.values?.[group] ?? {};
        const selected = selectedValues(filters, group);
        const keys = [...new Set([...Object.keys(counts), ...selected])]
          .filter((key) => group !== "SUBJECT" || selected.includes(key) || !isAssistant(key))
          .sort((a, b) => valueName(group, a).localeCompare(valueName(group, b)));
        const missingCount = facets.missing?.[group] ?? 0;
        const missingSelected = filters.missing.includes(group);
        if (!showsFacetGroup(keys, missingSelected)) return null;
        return (
          <fieldset key={group} className="flex flex-col gap-2 border-t border-line pt-3">
            <legend className="ui-sec float-left mb-2 w-full">{t(`facets.${group}`)}</legend>
            <div className="clear-left flex flex-wrap gap-1.5">
              {keys.map((key) => (
                <button
                  key={key}
                  type="button"
                  className="ui-pill"
                  aria-pressed={selected.includes(key)}
                  data-testid={`facet-${group}-${key}`}
                  onClick={() => onChange(toggled(filters, group, key))}
                >
                  {valueName(group, key)}
                  <span className="ui-pill-count">{counts[key] ?? 0}</span>
                </button>
              ))}
              {(missingCount > 0 || missingSelected) && (
                <button
                  type="button"
                  className="ui-pill border-dashed"
                  aria-pressed={missingSelected}
                  data-testid={`facet-missing-${group}`}
                  onClick={() => onChange(withoutMissing(filters, group))}
                >
                  {t("documents.missingValue")}
                  <span className="ui-pill-count">{missingCount}</span>
                </button>
              )}
            </div>
            {(group === "TOPIC" || group === "TAG") && selected.length > 1 && (
              <label className="flex items-center gap-2 text-sm text-text-3">
                {t("documents.matchMode")}
                <select
                  className="field w-auto"
                  value={group === "TOPIC" ? filters.topicMatch : filters.tagMatch}
                  aria-label={`${t("documents.matchMode")} ${t(`facets.${group}`)}`}
                  onChange={(event) =>
                    onChange({
                      ...filters,
                      page: 0,
                      topicMatch: group === "TOPIC" ? (event.target.value as "ANY" | "ALL") : filters.topicMatch,
                      tagMatch: group === "TAG" ? (event.target.value as "ANY" | "ALL") : filters.tagMatch,
                    })
                  }
                >
                  <option value="ANY">{t("documents.matchAny")}</option>
                  <option value="ALL">{t("documents.matchAll")}</option>
                </select>
              </label>
            )}
          </fieldset>
        );
      })}
    </div>
  );
}

/** The chosen filter values next to the filter button, each with a remove button (issue #27). */
export function ActiveFilters({ filters, labels, members, onChange }: Omit<Props, "facets">) {
  const { t } = useTranslation();
  const valueName = useValueName(labels, members);
  const chips = FACET_GROUPS.flatMap((group) => [
    ...selectedValues(filters, group).map((key) => ({
      key: `${group}:${key}`,
      facet: t(`facets.short.${group}`),
      name: valueName(group, key),
      remove: () => onChange(toggled(filters, group, key)),
    })),
    ...(filters.missing.includes(group)
      ? [{
          key: `missing:${group}`,
          facet: t(`facets.short.${group}`),
          name: t("documents.missingValue"),
          remove: () => onChange(withoutMissing(filters, group)),
        }]
      : []),
  ]);
  if (chips.length === 0) return null;
  return (
    <ul className="flex flex-wrap items-center gap-1.5" aria-label={t("documents.activeFilters")}>
      {chips.map((chip) => (
        <li key={chip.key}>
          <Chip facet={chip.facet} onRemove={chip.remove} removeName={`${chip.facet} ${chip.name}`}>
            {chip.name}
          </Chip>
        </li>
      ))}
    </ul>
  );
}
