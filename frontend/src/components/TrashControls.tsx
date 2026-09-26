import { useCallback, useState } from "react";
import { Link } from "react-router";
import { useTranslation } from "react-i18next";
import { useSession } from "../auth/SessionContext";
import { formatDate, formatDateTime } from "../lib/format";
import type { TrashInfo } from "../lib/trash";
import { Icon } from "./shell/icons";
import { Toast } from "./ui";

/**
 * Shown on a record that is in the trash: who put it there, since when, until when it is guaranteed to stay
 * restorable, and the restore action. Nothing about the record is gone; it is only hidden from ordinary views.
 */
export function TrashBanner({ trash, busy, onRestore }: { trash: TrashInfo; busy: boolean; onRestore(): void }) {
  const { t, i18n } = useTranslation();
  const { memberName } = useSession();
  return (
    <div className="card card-attention flex flex-col gap-2" role="status" data-testid="trash-banner">
      <p className="ui-eyebrow">
        <Icon name="trash" className="mr-1.5 inline h-3.5 w-3.5 align-[-2px]" />
        {t("trash.title")}
      </p>
      <p className="font-medium">
        {t("trash.banner", {
          name: memberName(trash.trashedBy) ?? t("app.unknownMember"),
          date: formatDateTime(trash.trashedAt, i18n.language),
        })}
      </p>
      <p className="font-mono text-xs text-text-3">{t("trash.restorableUntil", { date: formatDate(trash.restorableUntil, i18n.language) })}</p>
      <div className="flex flex-wrap items-center gap-2">
        <button type="button" className="btn-primary" disabled={busy} onClick={onRestore} data-testid="trash-restore">
          {t("trash.restore")}
        </button>
        <Link to="/trash" className="btn-ghost">
          {t("trash.open")}
        </Link>
      </div>
    </div>
  );
}

/**
 * "Move to trash" acts at once (issue #27): the trash is reversible, so the message that follows offers undo
 * instead of asking first. Typed confirmation stays reserved for irreversible actions.
 */
export function MoveToTrashButton({ busy, onTrash, testId }: { busy: boolean; onTrash(): void; testId: string }) {
  const { t } = useTranslation();
  return (
    <button type="button" className="btn-ghost -ml-4 self-start text-danger" disabled={busy} onClick={onTrash} data-testid={testId}>
      <Icon name="trash" className="h-4 w-4" />
      {t("trash.move")}
    </button>
  );
}

interface Trashable {
  id: string;
  version: number;
  trash?: TrashInfo | null;
}

/**
 * The message after a record went to the trash, with undo. The page keeps it, so it stays when the record leaves a
 * list or the context panel; the undo action should restore with the version the trash returned, so it never
 * overwrites a later change.
 */
export function useTrashUndo<T extends Trashable>() {
  const { t, i18n } = useTranslation();
  const [trashed, setTrashed] = useState<{ record: T; undo: () => void } | null>(null);
  const dismiss = useCallback(() => setTrashed(null), []);
  const notify = useCallback((record: T, undo: () => void) => setTrashed({ record, undo }), []);
  const until = trashed?.record.trash?.restorableUntil;
  const toast =
    trashed && until ? (
      <Toast
        key={`${trashed.record.id}-${trashed.record.version}`}
        title={t("trash.movedTitle")}
        onUndo={trashed.undo}
        onDismiss={dismiss}
        testId="trash-toast"
      >
        {t("trash.restorableUntil", { date: formatDate(until, i18n.language) })}
      </Toast>
    ) : null;
  return { notify, dismiss, toast };
}
