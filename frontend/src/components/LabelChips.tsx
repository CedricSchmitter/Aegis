import { useTranslation } from "react-i18next";
import type { LabelChipDto } from "../api/types";
import { labelName } from "../lib/labels";

const FACET_STYLES: Record<string, string> = {
  DOCUMENT_TYPE: "bg-sky-100 text-sky-900 dark:bg-sky-900 dark:text-sky-100",
  ORGANIZATION: "bg-violet-100 text-violet-900 dark:bg-violet-900 dark:text-violet-100",
  TOPIC: "bg-emerald-100 text-emerald-900 dark:bg-emerald-900 dark:text-emerald-100",
  TAG: "bg-stone-200 text-stone-800 dark:bg-stone-800 dark:text-stone-200",
};

interface Props {
  chips: LabelChipDto[];
  folderName?: (folderId: string) => string;
  onAccept?: (chip: LabelChipDto) => void;
  onReject?: (chip: LabelChipDto) => void;
}

/**
 * Label chips of a document. Folder chips carry a folder marker, suggestions are dashed and offer accept and
 * reject; a reported confidence is shown as the model score it is.
 */
export function LabelChips({ chips, folderName, onAccept, onReject }: Props) {
  const { t } = useTranslation();
  if (chips.length === 0) return null;
  return (
    <ul className="flex flex-wrap items-center gap-1" aria-label={t("documents.labels")}>
      {chips.map((chip) => {
        const suggested = chip.reviewState === "SUGGESTED";
        const inherited = chip.source === "FOLDER";
        const name = labelName(chip, t);
        const title = inherited && chip.folderId && folderName
          ? t("documents.fromFolder", { folder: folderName(chip.folderId) })
          : suggested
            ? t("documents.suggestionHint")
            : t(`facets.${chip.facet}`);
        return (
          <li key={chip.labelId}>
            <span
              className={`badge gap-1 ${FACET_STYLES[chip.facet] ?? FACET_STYLES.TAG} ${
                suggested ? "border border-dashed border-stone-500 opacity-80" : ""
              }`}
              title={title}
              data-testid="label-chip"
              data-label-id={chip.labelId}
              data-source={chip.source}
              data-review-state={chip.reviewState ?? ""}
            >
              {inherited && <span aria-hidden="true">🗂</span>}
              <span className="sr-only">{t(`facets.${chip.facet}`)}: </span>
              {name}
              {inherited && <span className="sr-only"> ({title})</span>}
              {suggested && (
                <span className="text-xs font-normal">
                  · {t("documents.suggestion")}
                  {chip.confidence != null && ` ${Math.round(chip.confidence * 100)} %`}
                </span>
              )}
            </span>
            {suggested && onAccept && onReject && (
              <span className="ml-1 inline-flex gap-1">
                <button type="button" className="btn-ghost min-h-9 px-2 py-1 text-xs"
                        onClick={() => onAccept(chip)} data-testid="accept-suggestion">
                  {t("documents.accept")}
                </button>
                <button type="button" className="btn-ghost min-h-9 px-2 py-1 text-xs"
                        onClick={() => onReject(chip)} data-testid="reject-suggestion">
                  {t("documents.reject")}
                </button>
              </span>
            )}
          </li>
        );
      })}
    </ul>
  );
}
