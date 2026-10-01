import { useCallback, useEffect, useState } from "react";
import { Link } from "react-router";
import { useTranslation } from "react-i18next";
import { api, toApiError, unwrap } from "../api/client";
import { useSession } from "../auth/SessionContext";
import { BigTitle, EmptyState, ErrorBox, Loading } from "../components/common";
import { ContextPanel, usePanelShown } from "../components/shell/ContextPanel";
import { StatusPanel } from "../components/status/StatusOverview";
import { Dialog, Toast } from "../components/ui";
import { failureReasonText } from "../lib/failureReasons";
import { formatDate, formatDateTime } from "../lib/format";
import { problemMessage } from "../lib/problem";
import { countTrash, filterTrash, hasPendingPurge, mergeTrash, TRASH_KINDS, type TrashEntry, type TrashKind } from "../lib/trash";

/** How often the view checks back while a document's permanent deletion runs in the background. */
const PURGE_POLL_MS = 3000;

/**
 * Everything the household moved to the trash (issue #27): documents, tasks and contracts with who trashed them, when,
 * and until when they are guaranteed to stay restorable. Each one can also be deleted permanently from here, and only
 * here, after a danger dialog (issue #85, ADR-0021). A document's deletion runs in the background, so the view checks
 * back until it is gone.
 */
export function TrashPage() {
  const { t, i18n } = useTranslation();
  const { memberName } = useSession();
  const panelShown = usePanelShown();
  const [entries, setEntries] = useState<TrashEntry[] | null>(null);
  const [kind, setKind] = useState<TrashKind | "all">("all");
  const [loadError, setLoadError] = useState<string | null>(null);
  // Kept apart from loadError: the reload after an action must not wipe the action's message.
  const [error, setError] = useState<string | null>(null);
  const [restored, setRestored] = useState<TrashEntry | null>(null);
  const [busy, setBusy] = useState<string | null>(null);
  const [confirming, setConfirming] = useState<TrashEntry | null>(null);
  const [purged, setPurged] = useState<{ entry: TrashEntry; finished: boolean } | null>(null);

  const load = useCallback(async () => {
    try {
      const [documents, tasks, contracts] = await Promise.all([
        api.GET("/api/documents/trash"),
        api.GET("/api/tasks/trash"),
        api.GET("/api/contracts/trash"),
      ]);
      setEntries(mergeTrash(unwrap(documents), unwrap(tasks), unwrap(contracts)));
      setLoadError(null);
    } catch (failure) {
      setLoadError(problemMessage(t, failure));
    }
  }, [t]);

  useEffect(() => {
    void load();
  }, [load]);

  useEffect(() => {
    if (!entries || !hasPendingPurge(entries)) return;
    const timer = window.setTimeout(() => void load(), PURGE_POLL_MS);
    return () => window.clearTimeout(timer);
  }, [entries, load]);

  const restore = async (entry: TrashEntry) => {
    setBusy(entry.id);
    setRestored(null);
    setError(null);
    try {
      const options = { params: { path: { id: entry.id } }, body: { expectedVersion: entry.version } };
      if (entry.kind === "document") unwrap(await api.POST("/api/documents/{id}/restore", options));
      else if (entry.kind === "task") unwrap(await api.POST("/api/tasks/{id}/restore", options));
      else unwrap(await api.POST("/api/contracts/{id}/restore", options));
      setRestored(entry);
    } catch (failure) {
      // Someone else changed it in the meantime: the reloaded list shows the current state.
      setError(toApiError(failure).status === 409 ? t("trash.changedMeanwhile") : problemMessage(t, failure));
    } finally {
      setBusy(null);
      await load();
    }
  };

  const purge = async (entry: TrashEntry) => {
    setBusy(entry.id);
    setRestored(null);
    setPurged(null);
    setError(null);
    try {
      const options = { params: { path: { id: entry.id } }, body: { expectedVersion: entry.version } };
      if (entry.kind === "document") unwrap(await api.POST("/api/documents/{id}/purge", options));
      else if (entry.kind === "task") unwrap(await api.POST("/api/tasks/{id}/purge", options));
      else unwrap(await api.POST("/api/contracts/{id}/purge", options));
      // A document leaves the archive in the background; tasks and contracts are gone at once.
      setPurged({ entry, finished: entry.kind !== "document" });
    } catch (failure) {
      const problem = toApiError(failure);
      setError(problem.status === 409 && problem.code === "version.conflict" ? t("trash.changedMeanwhile") : problemMessage(t, failure));
    } finally {
      setBusy(null);
      await load();
    }
  };

  const counts = entries ? countTrash(entries) : null;
  const visible = entries ? filterTrash(entries, kind) : [];

  return (
    <div className="flex max-w-3xl flex-col gap-4">
      <BigTitle count={counts ? String(counts.all) : null}>{t("trash.title")}</BigTitle>
      <p className="-mt-2 text-sm leading-relaxed text-text-3">{t("trash.intro")}</p>
      <ErrorBox message={error ?? loadError} />
      {!entries && !loadError && <Loading />}

      {counts && counts.all > 0 && (
        <div className="ui-rail lg:mx-0 lg:flex-wrap lg:px-0" role="group" aria-label={t("trash.filter")}>
          {(["all", ...TRASH_KINDS] as const).map((option) => (
            <button
              key={option}
              type="button"
              className="ui-pill"
              aria-pressed={option === kind}
              onClick={() => setKind(option)}
              data-testid={`trash-filter-${option}`}
            >
              {t(`trash.kindName.${option}`)}
              <span className="ui-pill-count">{counts[option]}</span>
            </button>
          ))}
        </div>
      )}

      {entries && visible.length === 0 && <EmptyState>{entries.length === 0 ? t("trash.empty") : t("trash.emptyKind")}</EmptyState>}

      {visible.length > 0 && (
        <ul className="flex flex-col gap-2" aria-label={t("trash.title")}>
          {visible.map((entry) => (
            <li key={`${entry.kind}-${entry.id}`} className="card flex flex-col gap-2" data-testid="trash-entry" data-kind={entry.kind}>
              <p className="ui-eyebrow">{t(`trash.kind.${entry.kind}`)}</p>
              <Link to={entry.link} className="ui-stretch self-start text-[15px] leading-snug font-semibold break-words hover:text-data">
                {entry.title}
              </Link>
              <p className="ui-meta">
                <span>
                  {t("trash.trashedBy", {
                    name: memberName(entry.trash.trashedBy) ?? t("app.unknownMember"),
                    date: formatDateTime(entry.trash.trashedAt, i18n.language),
                  })}
                </span>
                <span className="text-text-2">{t("trash.restorableUntil", { date: formatDate(entry.trash.restorableUntil, i18n.language) })}</span>
              </p>
              {entry.purge?.state === "PENDING" && (
                <p className="text-sm text-danger" role="status" data-testid="trash-entry-purging">
                  {t("trash.purging")}
                </p>
              )}
              {entry.purge?.state === "FAILED" && (
                <p className="text-sm text-danger" data-testid="trash-entry-purge-failed">
                  {t("trash.purgeFailed", { reason: failureReasonText(t, entry.purge.failureCode) })}
                </p>
              )}
              {!entry.purge && (
                <div className="flex flex-wrap gap-2">
                  <button
                    type="button"
                    className="btn-secondary"
                    disabled={busy !== null}
                    onClick={() => void restore(entry)}
                    data-testid="trash-entry-restore"
                  >
                    {t("trash.restore")}
                  </button>
                  <button
                    type="button"
                    className="btn-danger"
                    disabled={busy !== null}
                    onClick={() => setConfirming(entry)}
                    data-testid="trash-entry-purge"
                  >
                    {t("trash.purge")}
                  </button>
                </div>
              )}
              {entry.purge?.state === "FAILED" && (
                <div>
                  <button
                    type="button"
                    className="btn-danger"
                    disabled={busy !== null}
                    onClick={() => void purge(entry)}
                    data-testid="trash-entry-purge-retry"
                  >
                    {t("trash.purgeRetry")}
                  </button>
                </div>
              )}
            </li>
          ))}
        </ul>
      )}

      <Dialog
        open={confirming !== null}
        onClose={() => setConfirming(null)}
        variant="danger"
        eyebrow={t("ui.confirm.eyebrow")}
        title={confirming ? t("trash.purgeTitle", { title: confirming.title }) : ""}
        actions={
          <>
            <button type="button" className="btn-ghost" onClick={() => setConfirming(null)}>
              {t("app.cancel")}
            </button>
            <button
              type="button"
              className="btn-danger"
              data-testid="trash-purge-confirm"
              onClick={() => {
                const entry = confirming;
                setConfirming(null);
                if (entry) void purge(entry);
              }}
            >
              {t("trash.purge")}
            </button>
          </>
        }
      >
        <p className="text-sm leading-relaxed text-text-2">{confirming && t(`trash.purgeBody.${confirming.kind}`)}</p>
      </Dialog>

      {purged && (
        <Toast key={`purged-${purged.entry.kind}-${purged.entry.id}`} title={t("trash.purgedTitle")} onDismiss={() => setPurged(null)} testId="trash-purge-toast">
          {t(purged.finished ? "trash.purgedNamed" : "trash.purgeStarted", { title: purged.entry.title })}
        </Toast>
      )}
      {restored && (
        <Toast key={`${restored.kind}-${restored.id}`} title={t("trash.restoredTitle")} onDismiss={() => setRestored(null)} testId="trash-toast">
          {t("trash.restoredNamed", { title: restored.title })}
        </Toast>
      )}
      {panelShown && (
        <ContextPanel>
          <StatusPanel />
        </ContextPanel>
      )}
    </div>
  );
}
