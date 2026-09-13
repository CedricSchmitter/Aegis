import { useState } from "react";
import { Link } from "react-router";
import { useTranslation } from "react-i18next";
import { api, toApiError, unwrap } from "../api/client";
import type { TaskDto } from "../api/types";
import { useSession } from "../auth/SessionContext";
import { formatDate, formatDateTime, todayIso } from "../lib/format";
import { problemMessage } from "../lib/problem";
import { Badge } from "./common";

interface Props {
  task: TaskDto;
  onChange(task: TaskDto): void;
  detailed?: boolean;
}

/** One task with its actions. Every mutation carries the version the user saw; conflicts reload instead of overwriting. */
export function TaskItem({ task, onChange, detailed = false }: Props) {
  const { t, i18n } = useTranslation();
  const { members, memberName } = useSession();
  const [error, setError] = useState<string | null>(null);
  const [conflict, setConflict] = useState(false);
  const [busy, setBusy] = useState(false);
  const [editing, setEditing] = useState<"assign" | "plan" | "due" | "edit" | null>(null);
  const [draft, setDraft] = useState("");
  const [draftNote, setDraftNote] = useState("");

  const run = async (call: () => Promise<{ data?: TaskDto; error?: unknown; response: Response }>) => {
    setBusy(true);
    setError(null);
    setConflict(false);
    try {
      onChange(unwrap(await call()));
      setEditing(null);
    } catch (failure) {
      const apiError = toApiError(failure);
      if (apiError.status === 409) {
        setConflict(true);
        const fresh = await api.GET("/api/tasks/{id}", { params: { path: { id: task.id } } });
        if (fresh.data) onChange(fresh.data);
      } else {
        setError(problemMessage(t, failure));
      }
    } finally {
      setBusy(false);
    }
  };

  const overdue = task.status === "OPEN" && !!task.dueDate && task.dueDate < todayIso();
  const owner = memberName(task.ownerId);

  return (
    <article className="card flex flex-col gap-2" data-testid="task-item" data-task-id={task.id}>
      <div className="flex flex-wrap items-start justify-between gap-2">
        <div>
          <h3 className="font-semibold">
            {detailed ? task.title : <Link to={`/tasks/${task.id}`} className="hover:underline">{task.title}</Link>}
          </h3>
          <p className="text-sm text-stone-600 dark:text-stone-400">
            {owner ? t("tasks.ownerLabel", { name: owner }) : t("tasks.nobody")}
            {task.dueDate && (
              <>
                {" · "}
                <span className={overdue ? "font-semibold text-red-700 dark:text-red-300" : ""}>
                  {overdue
                    ? t("tasks.overdueBy", { date: formatDate(task.dueDate, i18n.language) })
                    : t("tasks.due", { date: formatDate(task.dueDate, i18n.language) })}
                </span>
              </>
            )}
            {task.plannedDate && <> · {t("tasks.planned", { date: formatDate(task.plannedDate, i18n.language) })}</>}
          </p>
          {task.source && (
            <p className="text-sm">
              <Link to={`/documents/${task.source.documentId}`} className="text-sky-800 hover:underline dark:text-sky-300">
                {task.source.documentTitle ? t("tasks.source", { title: task.source.documentTitle }) : t("tasks.sourceMissing")}
              </Link>
            </p>
          )}
          {detailed && task.note && <p className="mt-1 whitespace-pre-wrap text-sm">{task.note}</p>}
          {task.status === "DONE" && (
            <p className="text-sm text-stone-500">
              {t("tasks.doneBy", { name: memberName(task.completedBy) ?? t("app.unknownMember"), date: formatDateTime(task.completedAt, i18n.language) })}
            </p>
          )}
        </div>
        <Badge state={task.status} label={t(`tasks.status.${task.status}`)} />
      </div>

      {conflict && (
        <p className="alert-warn" role="alert">
          {t("tasks.conflict")}
        </p>
      )}
      {error && (
        <p className="alert-error" role="alert">
          {error}
        </p>
      )}

      <div className="flex flex-wrap gap-2">
        {task.status === "OPEN" ? (
          <button
            type="button"
            className="btn-primary"
            disabled={busy}
            data-testid="task-complete"
            onClick={() =>
              run(() => api.POST("/api/tasks/{id}/complete", { params: { path: { id: task.id } }, body: { expectedVersion: task.version } }))
            }
          >
            {t("tasks.complete")}
          </button>
        ) : (
          <button
            type="button"
            className="btn-secondary"
            disabled={busy}
            onClick={() =>
              run(() => api.POST("/api/tasks/{id}/reopen", { params: { path: { id: task.id } }, body: { expectedVersion: task.version } }))
            }
          >
            {t("tasks.reopen")}
          </button>
        )}
        {task.status === "OPEN" && (
          <>
            <button type="button" className="btn-secondary" disabled={busy} onClick={() => { setEditing("assign"); setDraft(task.ownerId ?? ""); }} data-testid="task-assign">
              {t("tasks.assign")}
            </button>
            <button type="button" className="btn-secondary" disabled={busy} onClick={() => { setEditing("plan"); setDraft(task.plannedDate ?? ""); }} data-testid="task-plan">
              {t("tasks.plan")}
            </button>
            <button type="button" className="btn-secondary" disabled={busy} onClick={() => { setEditing("due"); setDraft(task.dueDate ?? ""); }}>
              {t("tasks.changeDue")}
            </button>
            {detailed && (
              <button type="button" className="btn-secondary" disabled={busy} onClick={() => { setEditing("edit"); setDraft(task.title); setDraftNote(task.note ?? ""); }}>
                {t("tasks.edit")}
              </button>
            )}
          </>
        )}
      </div>

      {editing === "assign" && (
        <form
          className="flex flex-wrap items-end gap-2"
          onSubmit={(e) => {
            e.preventDefault();
            void run(() => api.POST("/api/tasks/{id}/assign", { params: { path: { id: task.id } }, body: { ownerId: draft || null, expectedVersion: task.version } }));
          }}
        >
          <label className="flex-1">
            <span className="label">{t("tasks.owner")}</span>
            <select className="field" value={draft} onChange={(e) => setDraft(e.target.value)} data-testid="task-owner-select">
              <option value="">{t("tasks.unassigned")}</option>
              {members.map((m) => (
                <option key={m.id} value={m.id}>
                  {m.displayName}
                </option>
              ))}
            </select>
          </label>
          <button type="submit" className="btn-primary" disabled={busy}>{t("app.save")}</button>
          <button type="button" className="btn-ghost" onClick={() => setEditing(null)}>{t("app.cancel")}</button>
        </form>
      )}
      {(editing === "plan" || editing === "due") && (
        <form
          className="flex flex-wrap items-end gap-2"
          onSubmit={(e) => {
            e.preventDefault();
            const value = draft || null;
            void run(() =>
              editing === "plan"
                ? api.POST("/api/tasks/{id}/plan", { params: { path: { id: task.id } }, body: { plannedDate: value, expectedVersion: task.version } })
                : api.POST("/api/tasks/{id}/due-date", { params: { path: { id: task.id } }, body: { dueDate: value, expectedVersion: task.version } }),
            );
          }}
        >
          <label className="flex-1">
            <span className="label">{editing === "plan" ? t("tasks.plannedDate") : t("tasks.dueDate")}</span>
            <input type="date" className="field" value={draft} onChange={(e) => setDraft(e.target.value)} data-testid="task-date-input" />
          </label>
          <p className="w-full text-xs text-stone-500">{t("tasks.dueHint")}</p>
          <button type="submit" className="btn-primary" disabled={busy}>{t("app.save")}</button>
          <button type="button" className="btn-ghost" onClick={() => setEditing(null)}>{t("app.cancel")}</button>
        </form>
      )}
      {editing === "edit" && (
        <form
          className="flex flex-col gap-2"
          onSubmit={(e) => {
            e.preventDefault();
            void run(() => api.PUT("/api/tasks/{id}", { params: { path: { id: task.id } }, body: { title: draft, note: draftNote, expectedVersion: task.version } }));
          }}
        >
          <label>
            <span className="label">{t("tasks.titleField")}</span>
            <input className="field" value={draft} onChange={(e) => setDraft(e.target.value)} required maxLength={200} />
          </label>
          <label>
            <span className="label">{t("tasks.note")}</span>
            <textarea className="field" rows={3} value={draftNote} onChange={(e) => setDraftNote(e.target.value)} maxLength={2000} />
          </label>
          <div className="flex gap-2">
            <button type="submit" className="btn-primary" disabled={busy}>{t("app.save")}</button>
            <button type="button" className="btn-ghost" onClick={() => setEditing(null)}>{t("app.cancel")}</button>
          </div>
        </form>
      )}
    </article>
  );
}
