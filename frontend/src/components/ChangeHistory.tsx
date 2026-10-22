import { useCallback, useEffect, useState } from "react";
import { useTranslation } from "react-i18next";
import { useSession } from "../auth/SessionContext";
import { formatDateTime } from "../lib/format";
import { appendPage, labelDelta, olderThanRecording, type HistoryEntry, type HistoryFieldChange, type HistoryPage } from "../lib/history";
import { problemMessage } from "../lib/problem";

interface Props {
  /** Changes when the record changed; an open history reloads then. */
  refreshKey: string;
  /** When the record was created, to say whether earlier changes were never recorded. */
  createdAt: string;
  load(next: number | null): Promise<HistoryPage>;
  /** Undoes the change and updates the record on the page; throws when the server refuses. */
  undo(entry: HistoryEntry): Promise<void>;
  /** A field value in words; labels are named one by one through {@code labelName}. */
  formatValue(field: string, value: string | null | undefined): string;
  labelName?(id: string): string;
  /** No undo while the record is in the trash; the history stays readable. */
  readOnly?: boolean;
}

/**
 * Who changed what and when, newest first (ADR-0029). Closed by default and loaded when opened, so a record's page
 * stays short on a phone. A member's own edit offers "Undo" while nothing newer replaced it; the undo is added as a
 * new entry and earlier entries never change.
 */
export function ChangeHistory({ refreshKey, createdAt, load, undo, formatValue, labelName, readOnly }: Props) {
  const { t, i18n } = useTranslation();
  const { memberName } = useSession();
  const [open, setOpen] = useState(false);
  const [entries, setEntries] = useState<HistoryEntry[] | null>(null);
  const [recordingSince, setRecordingSince] = useState<string | null>(null);
  const [next, setNext] = useState<number | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const reload = useCallback(async () => {
    try {
      const page = await load(null);
      setEntries(page.changes);
      setRecordingSince(page.recordingSince);
      setNext(page.next);
    } catch (failure) {
      setError(problemMessage(t, failure));
    }
  }, [load, t]);

  useEffect(() => {
    if (open) void reload();
    // The record changed elsewhere on the page: an open history shows the new entry.
  }, [open, refreshKey, reload]);

  const more = async () => {
    if (next == null) return;
    setBusy(true);
    try {
      const page = await load(next);
      setEntries((shown) => appendPage(shown ?? [], page.changes));
      setNext(page.next);
    } catch (failure) {
      setError(problemMessage(t, failure));
    } finally {
      setBusy(false);
    }
  };

  const takeBack = async (entry: HistoryEntry) => {
    setBusy(true);
    setError(null);
    try {
      await undo(entry);
    } catch (failure) {
      setError(problemMessage(t, failure));
      await reload();
    } finally {
      setBusy(false);
    }
  };

  const actor = (entry: HistoryEntry) => {
    const member = memberName(entry.actorMemberId) ?? t("app.unknownMember");
    if (entry.actorKind === "MEMBER") return member;
    const name =
      entry.actorKind === "AI_RUN"
        ? t("history.actor.aiRun")
        : t(`history.automation.${entry.actorReference}`, { defaultValue: t("history.actor.automation") });
    return entry.actorMemberId ? t("history.actor.triggered", { name, member }) : name;
  };

  const describe = (change: HistoryFieldChange) => {
    if (change.field === "labels") {
      const { added, removed } = labelDelta(change);
      const name = (id: string) => labelName?.(id) ?? t("history.value.unknownLabel");
      return [
        ...added.map((id) => t("history.labelAdded", { name: name(id) })),
        ...removed.map((id) => t("history.labelRemoved", { name: name(id) })),
      ].join(" · ");
    }
    return t("history.fromTo", { before: formatValue(change.field, change.before), after: formatValue(change.field, change.after) });
  };

  return (
    <details className="text-sm" data-testid="change-history" open={open} onToggle={(event) => setOpen(event.currentTarget.open)}>
      <summary className="flex min-h-11 cursor-pointer items-center font-mono text-xs tracking-[0.18em] text-text-3 uppercase">
        {t("history.title")}
      </summary>
      <div className="flex flex-col gap-3 pt-1">
        {error && (
          <p className="alert-error" role="alert">
            {error}
          </p>
        )}
        {entries == null && !error && <p className="text-text-3">{t("app.loading")}</p>}
        {entries != null && entries.length === 0 && <p className="text-text-3">{t("history.empty")}</p>}
        {entries != null && entries.length > 0 && (
          <ol className="flex flex-col gap-3" data-testid="history-entries">
            {entries.map((entry) => (
              <li key={entry.id} className="flex flex-col gap-1 border-l-2 border-line pl-3" data-testid="history-entry" data-action={entry.action}>
                <p className="font-mono text-xs text-text-3">
                  {actor(entry)} · {formatDateTime(entry.occurredAt, i18n.language)}
                </p>
                <p className="font-medium">{t(`history.action.${entry.action}`, { defaultValue: entry.action })}</p>
                {entry.changes.map((change) => (
                  <p key={change.field} className="line-clamp-3 break-words text-text-2">
                    <span className="text-text-3">{t(`history.field.${change.field}`, { defaultValue: change.field })}: </span>
                    {describe(change)}
                  </p>
                ))}
                {entry.undoneBy && <p className="text-xs text-text-3">{t("history.undone")}</p>}
                {entry.undoable && !readOnly && (
                  <div>
                    <button type="button" className="btn-ghost -ml-4" disabled={busy} onClick={() => void takeBack(entry)} data-testid="history-undo">
                      {t("history.undo")}
                    </button>
                  </div>
                )}
              </li>
            ))}
          </ol>
        )}
        {next != null && (
          <div>
            <button type="button" className="btn-ghost -ml-4" disabled={busy} onClick={() => void more()}>
              {t("history.more")}
            </button>
          </div>
        )}
        {entries != null && next == null && recordingSince && olderThanRecording(createdAt, recordingSince) && (
          <p className="text-xs text-text-3">{t("history.notRecordedBefore", { date: formatDateTime(recordingSince, i18n.language) })}</p>
        )}
      </div>
    </details>
  );
}
