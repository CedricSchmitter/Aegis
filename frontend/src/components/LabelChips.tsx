import { useTranslation } from "react-i18next";
import type { LabelChipDto } from "../api/types";
import { labelName } from "../lib/labels";
import { Diamond } from "./shell/icons";

interface Props {
  chips: LabelChipDto[];
  folderName?: (folderId: string) => string;
  onAccept?: (chip: LabelChipDto) => void;
  onReject?: (chip: LabelChipDto) => void;
  /** The short form of the document list: no facet names, no suggestion controls. */
  compact?: boolean;
}

/**
 * Label chips of a document in the data colour (ADR-0019). Folder chips carry a folder marker, suggestions are dashed
 * and offer accept and reject; a reported confidence is shown as the model score it is.
 */
export function LabelChips({ chips, folderName, onAccept, onReject, compact = false }: Props) {
  const { t } = useTranslation();
  if (chips.length === 0) return null;
  return (
    <ul className={`flex flex-wrap items-center ${compact ? "gap-1" : "gap-1.5"}`} aria-label={t("documents.labels")}>
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
          <li key={chip.labelId} className="flex flex-wrap items-center gap-1">
            <span
              className={`ui-chip ${compact ? "min-h-6 px-2 py-0.5 text-[11px]" : ""} ${
                suggested ? "outline-1 -outline-offset-1 outline-action outline-dashed" : ""
              }`}
              title={title}
              data-testid="label-chip"
              data-label-id={chip.labelId}
              data-source={chip.source}
              data-review-state={chip.reviewState ?? ""}
            >
              {inherited && <Diamond className="h-1.5 w-1.5 text-text-4" />}
              {compact ? (
                <span className="sr-only">{t(`facets.${chip.facet}`)}: </span>
              ) : (
                <small className="ui-chip-facet">
                  <span aria-hidden="true">{t(`facets.short.${chip.facet}`)}</span>
                  <span className="sr-only">{t(`facets.${chip.facet}`)}: </span>
                </small>
              )}
              {name}
              {inherited && <span className="sr-only"> ({title})</span>}
              {suggested && (
                <span className="text-text-3">
                  · {t("documents.suggestion")}
                  {chip.confidence != null && ` ${Math.round(chip.confidence * 100)} %`}
                </span>
              )}
            </span>
            {suggested && !compact && onAccept && onReject && (
              <span className="inline-flex gap-1">
                <button type="button" className="btn-secondary min-h-9 px-2.5 py-1 text-xs"
                        onClick={() => onAccept(chip)} data-testid="accept-suggestion">
                  {t("documents.accept")}
                </button>
                <button type="button" className="btn-ghost min-h-9 px-2.5 py-1 text-xs"
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
