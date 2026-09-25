import { useState } from "react";
import { useTranslation } from "react-i18next";
import { sheetMissing, type ScanPage } from "../scan/draft";
import type { ScanDraftState } from "../scan/useScanDraft";
import { PageEditor } from "./PageEditor";
import { useObjectUrl } from "./useObjectUrl";

interface Props {
  scan: ScanDraftState;
  disabled?: boolean;
  /** A page just taken: its detected sheet is shown at once for confirmation. */
  checkId?: string | null;
  onChecked?: () => void;
}

/**
 * The pages of the scan in progress: each as a thumbnail that opens the corner editor, with buttons to move,
 * rotate and remove it. Everything shown here becomes one PDF document when saved.
 */
export function ScanPages({ scan, disabled, checkId = null, onChecked }: Props) {
  const { t } = useTranslation();
  const [editing, setEditing] = useState<string | null>(null);
  const [confirmDiscard, setConfirmDiscard] = useState(false);
  // A page to check that no longer exists (its photo could not be opened) must not block the editor.
  const checking = checkId !== null && scan.pages.some((page) => page.id === checkId);
  const openId = checking ? checkId : editing;
  const editedIndex = scan.pages.findIndex((page) => page.id === openId);
  const edited = editedIndex >= 0 ? scan.pages[editedIndex] : null;
  const closeEditor = () => (checking ? onChecked?.() : setEditing(null));

  return (
    <div className="flex flex-col gap-3" data-testid="scan-pages">
      <p className="text-sm text-stone-600 dark:text-stone-400">{t("scan.hint", { count: scan.pages.length })}</p>
      <ol className="grid grid-cols-2 gap-3 sm:grid-cols-3">
        {scan.pages.map((page, index) => (
          <PageItem
            key={page.id}
            page={page}
            number={index + 1}
            first={index === 0}
            last={index === scan.pages.length - 1}
            disabled={disabled}
            onEdit={() => setEditing(page.id)}
            onMove={(delta) => scan.move(page.id, delta)}
            onRotate={() => scan.rotate(page.id)}
            onRemove={() => scan.remove(page.id)}
          />
        ))}
      </ol>
      {scan.pages.some(sheetMissing) && (
        <p className="alert-warn" data-testid="scan-sheet-missing-hint">
          {t("scan.sheetMissingHint")}
        </p>
      )}
      <div>
        {confirmDiscard ? (
          <span className="flex flex-wrap items-center gap-2">
            <span className="text-sm">{t("scan.discardQuestion", { count: scan.pages.length })}</span>
            <button
              type="button"
              className="btn-secondary"
              disabled={disabled}
              data-testid="scan-discard-confirm"
              onClick={() => {
                setConfirmDiscard(false);
                void scan.clear();
              }}
            >
              {t("scan.discardConfirm")}
            </button>
            <button type="button" className="btn-ghost" onClick={() => setConfirmDiscard(false)}>
              {t("scan.discardCancel")}
            </button>
          </span>
        ) : (
          <button
            type="button"
            className="btn-ghost -ml-4"
            disabled={disabled}
            data-testid="scan-discard"
            onClick={() => setConfirmDiscard(true)}
          >
            {t("scan.discard")}
          </button>
        )}
      </div>
      {edited && (
        <PageEditor
          key={edited.id}
          page={edited}
          number={editedIndex + 1}
          purpose={checking ? "check" : "edit"}
          onApply={(corners) => {
            scan.applyCorners(edited.id, corners);
            closeEditor();
          }}
          onKeepPhoto={() => {
            scan.keepPhoto(edited.id);
            closeEditor();
          }}
          onRemove={() => {
            scan.remove(edited.id);
            closeEditor();
          }}
          onClose={closeEditor}
        />
      )}
    </div>
  );
}

interface ItemProps {
  page: ScanPage;
  number: number;
  first: boolean;
  last: boolean;
  disabled?: boolean;
  onEdit: () => void;
  onMove: (delta: number) => void;
  onRotate: () => void;
  onRemove: () => void;
}

function PageItem({ page, number, first, last, disabled, onEdit, onMove, onRotate, onRemove }: ItemProps) {
  const { t } = useTranslation();
  const thumbnail = useObjectUrl(page.result?.thumbnail);
  const processing = page.result === null;
  const note = sheetMissing(page)
    ? t("scan.sheetMissing")
    : page.result?.mode === "photo"
      ? t("scan.photoKept")
      : null;

  return (
    <li
      className="flex flex-col gap-2 rounded-lg border border-stone-200 p-2 dark:border-stone-700"
      data-testid="scan-page"
      data-mode={page.result?.mode ?? "processing"}
    >
      <button
        type="button"
        className="flex aspect-[1/1.414] w-full items-center justify-center overflow-hidden rounded-md bg-stone-100 dark:bg-stone-800"
        disabled={disabled || processing}
        onClick={onEdit}
        aria-label={t("scan.edit", { number })}
        data-testid="scan-page-edit"
      >
        {processing ? (
          <span className="px-2 text-center text-xs text-stone-600 dark:text-stone-300" aria-busy="true">
            {t("scan.processing")}
          </span>
        ) : (
          thumbnail && <img src={thumbnail} alt="" className="h-full w-full object-contain" />
        )}
      </button>
      <div className="flex items-center justify-between gap-1">
        <span className="text-sm font-medium">{t("scan.page", { number })}</span>
      </div>
      {note && <span className="text-xs text-amber-800 dark:text-amber-300">{note}</span>}
      <div className="grid grid-cols-4 gap-1">
        <IconButton label={t("scan.moveEarlier", { number })} disabled={disabled || first} onClick={() => onMove(-1)}>
          ←
        </IconButton>
        <IconButton label={t("scan.moveLater", { number })} disabled={disabled || last} onClick={() => onMove(1)}>
          →
        </IconButton>
        <IconButton label={t("scan.rotate", { number })} disabled={disabled || processing} onClick={onRotate} testId="scan-page-rotate">
          ↻
        </IconButton>
        <IconButton label={t("scan.remove", { number })} disabled={disabled} onClick={onRemove} testId="scan-page-remove">
          ✕
        </IconButton>
      </div>
    </li>
  );
}

function IconButton({
  label,
  disabled,
  onClick,
  testId,
  children,
}: {
  label: string;
  disabled?: boolean;
  onClick: () => void;
  testId?: string;
  children: string;
}) {
  return (
    <button
      type="button"
      className="btn-secondary min-w-0 px-0"
      aria-label={label}
      title={label}
      disabled={disabled}
      onClick={onClick}
      data-testid={testId}
    >
      <span aria-hidden="true">{children}</span>
    </button>
  );
}
