import { Link } from "react-router";
import { useTranslation } from "react-i18next";
import type { DocumentDto } from "../api/types";
import { useSession } from "../auth/SessionContext";
import { formatDate } from "../lib/format";
import { Badge } from "./common";
import { LabelChips } from "./LabelChips";
import { SubjectList } from "./SubjectList";

/** Analysis states worth a badge in lists; finished or never-requested analyses stay quiet. */
const VISIBLE_ANALYSIS = new Set(["WAITING_FOR_ARCHIVE", "PENDING", "NEEDS_REVIEW", "FAILED", "UNAVAILABLE"]);

interface Props {
  document: DocumentDto;
  selected?: boolean;
  onSelectedChange?: (selected: boolean) => void;
  folderName?: (folderId: string) => string;
}

export function DocumentCard({ document, selected, onSelectedChange, folderName }: Props) {
  const { t, i18n } = useTranslation();
  const { memberName } = useSession();
  const state = document.processing.state;
  const path = document.classification.path.map((step) => step.name).join(" / ");
  return (
    <article className="card flex flex-col gap-1" data-testid="document-card" data-document-id={document.id}>
      <div className="flex flex-wrap items-start justify-between gap-2">
        <div className="flex items-start gap-2">
          {onSelectedChange && (
            <input
              type="checkbox"
              className="mt-1 h-5 w-5"
              checked={selected ?? false}
              aria-label={t("documents.select")}
              data-testid="select-document"
              onChange={(event) => onSelectedChange(event.target.checked)}
            />
          )}
          <h3 className="font-semibold">
            <Link to={`/documents/${document.id}`} className="hover:underline">
              {document.title}
            </Link>
          </h3>
        </div>
        <span className="flex flex-wrap gap-1">
          <Badge state={state} label={t(`documents.stateShort.${state}`)} />
          {VISIBLE_ANALYSIS.has(document.analysis.state) && (
            <Badge
              state={document.analysis.state}
              label={t(`analysis.stateShort.${document.analysis.state}`)}
            />
          )}
          {document.classification.placementSuggestion && (
            <Badge state="NEEDS_REVIEW" label={t("placement.badge")} />
          )}
        </span>
      </div>
      <p className="text-sm text-stone-600 dark:text-stone-400">
        {t("documents.uploadedBy", {
          name: memberName(document.uploadedBy) ?? t("app.unknownMember"),
          date: formatDate(document.uploadedAt, i18n.language),
        })}
      </p>
      <p className="text-xs text-stone-500" data-testid="document-folder">
        🗂 {path || t("documents.unsorted")}
        {document.classification.placement.origin === "AUTOMATIC" && ` · ${t("placement.automaticShort")}`}
      </p>
      <LabelChips chips={document.classification.labels} folderName={folderName} />
      <SubjectList subjects={document.subjects} />
    </article>
  );
}
