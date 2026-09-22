import { useState } from "react";
import { Link } from "react-router";
import { useTranslation } from "react-i18next";
import { useSession } from "../auth/SessionContext";
import { formatDate, formatDateTime } from "../lib/format";
import type { TrashInfo } from "../lib/trash";

/**
 * Shown on a record that is in the trash: who put it there, since when, until when it is guaranteed to stay
 * restorable, and the restore action. Nothing about the record is gone; it is only hidden from ordinary views.
 */
export function TrashBanner({ trash, busy, onRestore }: { trash: TrashInfo; busy: boolean; onRestore(): void }) {
  const { t, i18n } = useTranslation();
  const { memberName } = useSession();
  return (
    <div className="alert-warn mb-4 flex flex-col gap-2" role="status" data-testid="trash-banner">
      <p className="font-medium">
        {t("trash.banner", {
          name: memberName(trash.trashedBy) ?? t("app.unknownMember"),
          date: formatDateTime(trash.trashedAt, i18n.language),
        })}
      </p>
      <p className="text-sm">{t("trash.restorableUntil", { date: formatDate(trash.restorableUntil, i18n.language) })}</p>
      <div className="flex flex-wrap items-center gap-3">
        <button type="button" className="btn-primary" disabled={busy} onClick={onRestore} data-testid="trash-restore">
          {t("trash.restore")}
        </button>
        <Link to="/trash" className="text-sm underline">
          {t("trash.open")}
        </Link>
      </div>
    </div>
  );
}

/**
 * "Move to trash" with a short confirmation in place, so a mistap on the phone does nothing. The confirmation says
 * what happens: the record disappears from lists and can be restored from the trash.
 */
export function MoveToTrashButton({ busy, onConfirm, testId }: { busy: boolean; onConfirm(): void; testId: string }) {
  const { t } = useTranslation();
  const [confirming, setConfirming] = useState(false);
  if (!confirming) {
    return (
      <button type="button" className="btn-secondary" disabled={busy} onClick={() => setConfirming(true)} data-testid={testId}>
        {t("trash.move")}
      </button>
    );
  }
  return (
    <div className="card flex flex-col gap-2" role="group" aria-label={t("trash.move")}>
      <p className="text-sm">{t("trash.confirm")}</p>
      <div className="flex flex-wrap gap-2">
        <button
          type="button"
          className="btn-primary"
          disabled={busy}
          onClick={() => {
            setConfirming(false);
            onConfirm();
          }}
          data-testid={`${testId}-confirm`}
        >
          {t("trash.moveConfirm")}
        </button>
        <button type="button" className="btn-ghost" onClick={() => setConfirming(false)}>
          {t("app.cancel")}
        </button>
      </div>
    </div>
  );
}
