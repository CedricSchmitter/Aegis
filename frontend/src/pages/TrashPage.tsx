import { useCallback, useEffect, useState } from "react";
import { Link } from "react-router";
import { useTranslation } from "react-i18next";
import { api, toApiError, unwrap } from "../api/client";
import { useSession } from "../auth/SessionContext";
import { EmptyState, ErrorBox, InfoBox, Loading, PageTitle } from "../components/common";
import { formatDate, formatDateTime } from "../lib/format";
import { problemMessage } from "../lib/problem";
import { countTrash, filterTrash, mergeTrash, TRASH_KINDS, type TrashEntry, type TrashKind } from "../lib/trash";

/**
 * Everything the household moved to the trash: documents, tasks and contracts with who trashed them, when, and
 * until when they are guaranteed to stay restorable. Nothing is deleted permanently from here.
 */
export function TrashPage() {
  const { t, i18n } = useTranslation();
  const { memberName } = useSession();
  const [entries, setEntries] = useState<TrashEntry[] | null>(null);
  const [kind, setKind] = useState<TrashKind | "all">("all");
  const [error, setError] = useState<string | null>(null);
  const [message, setMessage] = useState<string | null>(null);
  const [busy, setBusy] = useState<string | null>(null);

  const load = useCallback(async () => {
    try {
      const [documents, tasks, contracts] = await Promise.all([
        api.GET("/api/documents/trash"),
        api.GET("/api/tasks/trash"),
        api.GET("/api/contracts/trash"),
      ]);
      setEntries(mergeTrash(unwrap(documents), unwrap(tasks), unwrap(contracts)));
      setError(null);
    } catch (failure) {
      setError(problemMessage(t, failure));
    }
  }, [t]);

  useEffect(() => {
    void load();
  }, [load]);

  const restore = async (entry: TrashEntry) => {
    setBusy(entry.id);
    setMessage(null);
    setError(null);
    try {
      const options = { params: { path: { id: entry.id } }, body: { expectedVersion: entry.version } };
      if (entry.kind === "document") unwrap(await api.POST("/api/documents/{id}/restore", options));
      else if (entry.kind === "task") unwrap(await api.POST("/api/tasks/{id}/restore", options));
      else unwrap(await api.POST("/api/contracts/{id}/restore", options));
      setMessage(t("trash.restoredNamed", { title: entry.title }));
    } catch (failure) {
      // Someone else changed it in the meantime: the reloaded list shows the current state.
      setError(toApiError(failure).status === 409 ? t("trash.changedMeanwhile") : problemMessage(t, failure));
    } finally {
      setBusy(null);
      await load();
    }
  };

  const counts = entries ? countTrash(entries) : null;
  const visible = entries ? filterTrash(entries, kind) : [];

  return (
    <>
      <PageTitle>{t("trash.title")}</PageTitle>
      <p className="mb-3 text-sm text-stone-600 dark:text-stone-400">{t("trash.intro")}</p>
      <ErrorBox message={error} />
      {message && <InfoBox>{message}</InfoBox>}
      {!entries && !error && <Loading />}

      {counts && (
        <div className="mb-3 flex flex-wrap gap-2" role="group" aria-label={t("trash.filter")}>
          {(["all", ...TRASH_KINDS] as const).map((option) => (
            <button
              key={option}
              type="button"
              className={option === kind ? "btn-primary" : "btn-secondary"}
              aria-pressed={option === kind}
              onClick={() => setKind(option)}
              data-testid={`trash-filter-${option}`}
            >
              {t(`trash.kinds.${option}`, { count: counts[option] })}
            </button>
          ))}
        </div>
      )}

      {entries && visible.length === 0 && <EmptyState>{t("trash.empty")}</EmptyState>}

      <ul className="flex flex-col gap-2">
        {visible.map((entry) => (
          <li key={`${entry.kind}-${entry.id}`} className="card flex flex-col gap-1" data-testid="trash-entry">
            <div className="flex flex-wrap items-center justify-between gap-2">
              <div>
                <span className="mr-2 text-xs uppercase tracking-wide text-stone-500">{t(`trash.kind.${entry.kind}`)}</span>
                <Link to={entry.link} className="font-medium text-sky-800 hover:underline dark:text-sky-300">
                  {entry.title}
                </Link>
              </div>
              <button
                type="button"
                className="btn-secondary"
                disabled={busy !== null}
                onClick={() => void restore(entry)}
                data-testid="trash-entry-restore"
              >
                {t("trash.restore")}
              </button>
            </div>
            <p className="text-sm text-stone-600 dark:text-stone-400">
              {t("trash.trashedBy", {
                name: memberName(entry.trash.trashedBy) ?? t("app.unknownMember"),
                date: formatDateTime(entry.trash.trashedAt, i18n.language),
              })}
              {" · "}
              {t("trash.restorableUntil", { date: formatDate(entry.trash.restorableUntil, i18n.language) })}
            </p>
          </li>
        ))}
      </ul>
    </>
  );
}
