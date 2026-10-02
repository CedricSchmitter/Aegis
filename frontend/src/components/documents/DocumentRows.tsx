import type { MouseEvent } from "react";
import { Link, useNavigate } from "react-router";
import { useTranslation } from "react-i18next";
import type { DocumentDto } from "../../api/types";
import { documentBadges } from "../../lib/documentBadges";
import { fileKind } from "../../lib/documentView";
import { formatDate } from "../../lib/format";
import { Badge } from "../common";
import { LabelChips } from "../LabelChips";
import { FileTypeTag } from "../ui";

/** "Haus / Rechnungen", or Unsorted; automatic placements say so. */
function FolderPath({ document }: { document: DocumentDto }) {
  const { t } = useTranslation();
  const path = document.classification.path.map((step) => step.name).join(" / ");
  return (
    <span className="min-w-0 break-words" data-testid="document-folder">
      {path || t("documents.unsorted")}
      {document.classification.placement.origin === "AUTOMATIC" && ` · ${t("placement.automaticShort")}`}
    </span>
  );
}

/** State badges only where a document deviates from archived, readable and settled; found by data-state. */
export function StateBadges({ document }: { document: DocumentDto }) {
  const { t } = useTranslation();
  const badges = documentBadges(document);
  if (badges.length === 0) return null;
  return (
    <span className="flex flex-wrap gap-1.5">
      {badges.map((badge) => (
        <Badge key={badge.labelKey} state={badge.state} label={t(badge.labelKey)} />
      ))}
    </span>
  );
}

interface SelectProps {
  selected: boolean;
  onSelectedChange?: (selected: boolean) => void;
}

function SelectBox({ selected, onSelectedChange }: SelectProps) {
  const { t } = useTranslation();
  if (!onSelectedChange) return null;
  return (
    <label className="-my-2.5 -mr-2 grid h-11 w-11 cursor-pointer place-items-center" data-above>
      <input
        type="checkbox"
        className="ui-checkbox"
        checked={selected}
        aria-label={t("documents.select")}
        data-testid="select-document"
        onChange={(event) => onSelectedChange(event.target.checked)}
      />
    </label>
  );
}

interface CardProps extends SelectProps {
  document: DocumentDto;
  folderName?: (folderId: string) => string;
}

/** A document in the phone list; the whole card opens it. */
export function DocumentCard({ document, selected, onSelectedChange, folderName }: CardProps) {
  const { i18n } = useTranslation();
  return (
    <li className="doc-card" data-testid="document-card" data-document-id={document.id}>
      <span className="mt-0.5">
        <FileTypeTag kind={fileKind(document)} />
      </span>
      <div className="flex min-w-0 flex-col gap-1">
        <h3 className="text-[15px] leading-snug font-semibold break-words">
          <Link to={`/documents/${document.id}`} className="doc-link">
            {document.title}
          </Link>
        </h3>
        <p className="flex flex-wrap gap-x-3 gap-y-0.5 font-mono text-xs text-text-3">
          <span>{formatDate(document.uploadedAt, i18n.language)}</span>
          <FolderPath document={document} />
        </p>
        <LabelChips chips={document.classification.labels} folderName={folderName} compact />
        <StateBadges document={document} />
      </div>
      <SelectBox selected={selected} onSelectedChange={onSelectedChange} />
    </li>
  );
}

interface TableProps {
  items: DocumentDto[];
  selection: string[];
  onSelectionChange: (id: string, selected: boolean) => void;
  /** The document shown in the context panel; null below 1280 px, where a click opens the document. */
  previewed: string | null;
  onPreview: ((id: string) => void) | null;
  folderName?: (folderId: string) => string;
}

/** Whether a click on the title is a plain single mouse click, which previews instead of opening. */
function isPreviewClick(event: MouseEvent<HTMLAnchorElement>): boolean {
  return event.detail > 0 && event.button === 0 && !event.ctrlKey && !event.metaKey && !event.shiftKey && !event.altKey;
}

/**
 * The desktop table. With the context panel shown, a click selects a document and shows it on the right; a double
 * click, Enter on the title or "Open large" opens it. Without the panel the title simply opens the document.
 */
export function DocumentTable({ items, selection, onSelectionChange, previewed, onPreview, folderName }: TableProps) {
  const { t, i18n } = useTranslation();
  const navigate = useNavigate();
  return (
    <div role="table" aria-label={t("documents.title")} className="doc-table">
      <div role="rowgroup">
        <div role="row" data-head>
          <span role="columnheader">
            <span className="sr-only">{t("documents.select")}</span>
          </span>
          <span role="columnheader">{t("documents.column.type")}</span>
          <span role="columnheader">{t("documents.column.name")}</span>
          <span role="columnheader">{t("documents.column.state")}</span>
          <span role="columnheader" className="text-right">
            {t("documents.column.date")}
          </span>
        </div>
      </div>
      <div role="rowgroup">
        {items.map((document) => {
          const isPreviewed = onPreview !== null && previewed === document.id;
          return (
            <div
              key={document.id}
              role="row"
              className={isPreviewed ? "ui-corners" : undefined}
              data-selected={isPreviewed}
              data-testid="document-card"
              data-document-id={document.id}
              onClick={(event) => {
                if ((event.target as HTMLElement).closest("input, label, button")) return;
                onPreview?.(document.id);
              }}
              onDoubleClick={() => onPreview && navigate(`/documents/${document.id}`)}
              onFocus={() => onPreview?.(document.id)}
            >
              <span role="cell">
                <SelectBox
                  selected={selection.includes(document.id)}
                  onSelectedChange={(checked) => onSelectionChange(document.id, checked)}
                />
              </span>
              <span role="cell">
                <FileTypeTag kind={fileKind(document)} />
              </span>
              <span role="cell" className="flex min-w-0 flex-col gap-1.5">
                <Link
                  to={`/documents/${document.id}`}
                  className="doc-link text-[14.5px] leading-snug font-semibold break-words hover:underline"
                  onClick={(event) => {
                    if (onPreview && isPreviewClick(event)) {
                      event.preventDefault();
                      onPreview(document.id);
                    }
                  }}
                >
                  {document.title}
                </Link>
                <span className="flex flex-wrap items-center gap-x-2 gap-y-1 font-mono text-xs text-text-3">
                  <FolderPath document={document} />
                  <LabelChips chips={document.classification.labels} folderName={folderName} compact />
                </span>
              </span>
              <span role="cell">
                <StateBadges document={document} />
              </span>
              <span role="cell" className="text-right font-mono text-xs text-text-3">
                {formatDate(document.uploadedAt, i18n.language)}
              </span>
            </div>
          );
        })}
      </div>
    </div>
  );
}
