import { useTranslation } from "react-i18next";
import { FACET_GROUPS, type FacetCounts, type FacetGroup, type LabelDto, type MemberDto } from "../api/types";
import { facetParam, selectedValues, showsFacetGroup, toggleValue, type CatalogFilters } from "../lib/catalogSearch";
import { byId, labelName, subjectFromKey } from "../lib/labels";

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
  const known = byId(labels);

  const subjectName = (key: string) => {
    const subject = subjectFromKey(key);
    if (subject.kind === "MEMBER") {
      const member = members.find((entry) => entry.id === subject.memberId);
      return t("subjects.MEMBER", { name: member?.displayName ?? t("app.unknownMember") });
    }
    return t(`subjects.${subject.kind}`);
  };

  // The assistant account is never what a document is about, so the subject filter offers only persons (ADR-0017).
  const isAssistant = (key: string) => {
    const subject = subjectFromKey(key);
    return subject.kind === "MEMBER" && members.find((entry) => entry.id === subject.memberId)?.kind === "ASSISTANT";
  };

  const valueName = (group: FacetGroup, key: string) =>
    group === "SUBJECT" ? subjectName(key) : labelName(known.get(key), t) || key;

  const update = (group: FacetGroup, value: string) => {
    const next = { ...filters, page: 0 };
    if (group === "SUBJECT") next.subject = toggleValue(filters.subject, value);
    else next[facetParam(group)] = toggleValue(selectedValues(filters, group), value);
    onChange(next);
  };

  const toggleMissing = (group: FacetGroup) =>
    onChange({ ...filters, page: 0, missing: toggleValue(filters.missing, group) as FacetGroup[] });

  return (
    <div className="flex flex-col gap-3" data-testid="facet-filters">
      <p className="text-xs text-stone-500">{t("documents.filterLogic")}</p>
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
          <fieldset key={group} className="flex flex-col gap-1">
            <legend className="label mb-0">{t(`facets.${group}`)}</legend>
            <div className="flex flex-wrap gap-1">
              {keys.map((key) => {
                const isSelected = selected.includes(key);
                return (
                  <button
                    key={key}
                    type="button"
                    aria-pressed={isSelected}
                    data-testid={`facet-${group}-${key}`}
                    className={`badge min-h-9 gap-1 border px-3 ${
                      isSelected
                        ? "border-sky-700 bg-sky-100 text-sky-900 dark:border-sky-400 dark:bg-sky-900 dark:text-sky-100"
                        : "border-stone-300 bg-white text-stone-800 dark:border-stone-700 dark:bg-stone-900 dark:text-stone-200"
                    }`}
                    onClick={() => update(group, key)}
                  >
                    {valueName(group, key)}
                    <span className="text-xs text-stone-500">{counts[key] ?? 0}</span>
                  </button>
                );
              })}
              {(missingCount > 0 || missingSelected) && (
                <button
                  type="button"
                  aria-pressed={filters.missing.includes(group)}
                  data-testid={`facet-missing-${group}`}
                  className={`badge min-h-9 gap-1 border border-dashed px-3 ${
                    filters.missing.includes(group)
                      ? "border-sky-700 bg-sky-100 text-sky-900 dark:border-sky-400 dark:bg-sky-900 dark:text-sky-100"
                      : "border-stone-400 bg-white text-stone-700 dark:bg-stone-900 dark:text-stone-300"
                  }`}
                  onClick={() => toggleMissing(group)}
                >
                  {t("documents.missingValue")}
                  <span className="text-xs text-stone-500">{missingCount}</span>
                </button>
              )}
            </div>
            {(group === "TOPIC" || group === "TAG") && selected.length > 1 && (
              <label className="flex items-center gap-2 text-xs text-stone-600 dark:text-stone-400">
                {t("documents.matchMode")}
                <select
                  className="rounded border border-stone-300 bg-white px-1 py-0.5 dark:border-stone-700 dark:bg-stone-900"
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
