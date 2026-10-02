import { useState, type ReactNode } from "react";
import { Link } from "react-router";
import { useTranslation } from "react-i18next";
import { api } from "../../api/client";
import type { TaskDto } from "../../api/types";
import { useSession } from "../../auth/SessionContext";
import { formatDate, formatDateTime, todayIso } from "../../lib/format";
import { daysUntil, isOverdue } from "../../lib/tasks";
import { MoveToTrashButton, TrashBanner } from "../TrashControls";
import { OwnerMark, useTaskTitle } from "./TaskCard";
import { useTaskMutation } from "./useTaskMutation";

interface Props {
  task: TaskDto;
  onChange(task: TaskDto): void;
  /** The title as a page heading or as the heading of the context panel. */
  heading: (title: string) => ReactNode;
  /** After "Done", for the message with undo. */
  onCompleted?(task: TaskDto): void;
  /** After "Move to trash", for the message with undo; the page keeps it, because the task may leave its list. */
  onTrashed?(task: TaskDto): void;
}

type Editing = "assign" | "plan" | "due" | "edit" | null;

/**
 * One task: deadline, planned work day and responsibility, the source, then "Done" as the one primary
 * action with assigning and rescheduling below it. Rescheduling changes the work day only; the contractual deadline
 * of a cancellation review cannot be changed here at all.
 */
export function TaskDetail({ task, onChange, heading, onCompleted, onTrashed }: Props) {
  const { t, i18n } = useTranslation();
  const { members, memberName } = useSession();
  const title = useTaskTitle(task);
  const { busy, error, conflict, run, complete, reopen } = useTaskMutation(task, onChange);
  const [editing, setEditing] = useState<Editing>(null);
  const [draft, setDraft] = useState("");
  const [draftNote, setDraftNote] = useState("");

  const today = todayIso();
  const overdue = isOverdue(task, today);
  const review = task.cancellationReview;
  const remaining = review ? daysUntil(review.cancellationDeadline, today) : null;
  const open = task.status === "OPEN";
  const path = { params: { path: { id: task.id } } };
  const save = async (call: Parameters<typeof run>[0]) => {
    if (await run(call)) setEditing(null);
  };
  const start = (mode: Editing, value: string) => {
    setEditing(mode);
    setDraft(value);
  };

  return (
    <article className="flex flex-col gap-4" data-testid="task-detail" data-task-id={task.id}>
      <div className="flex flex-col gap-1.5">
        <p className="ui-eyebrow">
          {t("tasks.eyebrow")} · <b>{t(`tasks.status.${task.status}`, { defaultValue: task.status })}</b>
        </p>
        {heading(title)}
      </div>

      {task.trash && (
        <TrashBanner
          trash={task.trash}
          busy={busy}
          onRestore={() => void run(() => api.POST("/api/tasks/{id}/restore", { ...path, body: { expectedVersion: task.version } }))}
        />
      )}
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

      <div className="card flex flex-col gap-3">
        <dl className="ui-kv">
          <dt>{t("tasks.deadlineLabel")}</dt>
          <dd className={`font-mono ${overdue ? "text-danger" : ""}`} data-testid="task-due-date">
            {task.dueDate ? formatDate(task.dueDate, i18n.language) : "—"}
            {overdue && ` · ${t("tasks.overdueShort")}`}
          </dd>
          <dt>{t("tasks.plannedLabel")}</dt>
          <dd className="font-mono text-data" data-testid="task-planned-date">
            {task.plannedDate ? formatDate(task.plannedDate, i18n.language) : "—"}
          </dd>
          <dt>{t("tasks.owner")}</dt>
          <dd className="flex items-center justify-end gap-2">
            {memberName(task.ownerId) ?? t("tasks.nobodyShort")}
            {task.ownerId && <OwnerMark ownerId={task.ownerId} />}
          </dd>
        </dl>
        <p className="text-[13px] leading-relaxed text-text-3">{t("tasks.dueHint")}</p>
      </div>

      {task.status === "DONE" && (
        <p className="text-sm text-text-3">
          {t("tasks.doneBy", {
            name: memberName(task.completedBy) ?? t("app.unknownMember"),
            date: formatDateTime(task.completedAt, i18n.language),
          })}
        </p>
      )}
      {task.note && <p className="text-sm whitespace-pre-wrap text-text-2">{task.note}</p>}

      {task.source && task.source.documentDeleted && (
        // A source deleted permanently (ADR-0021) has no page to open any more.
        <div className="card flex flex-col gap-1" data-testid="task-source-deleted">
          <span className="ui-eyebrow">{t("tasks.sourceLabel")}</span>
          <span className="text-[15px] leading-snug font-semibold break-words text-text-3">{t("trash.sourceDeleted")}</span>
        </div>
      )}
      {task.source && !task.source.documentDeleted && (
        <Link to={`/documents/${task.source.documentId}`} className="card flex flex-col gap-1">
          <span className="ui-eyebrow">{t("tasks.sourceLabel")}</span>
          <span className="text-[15px] leading-snug font-semibold break-words">
            {task.source.documentTitle ?? t("tasks.sourceMissing")}
          </span>
          {task.source.documentTrashed && <span className="text-sm text-text-3">{t("trash.sourceInTrash")}</span>}
        </Link>
      )}

      {review && review.contractDeleted && (
        // A completed review kept as history after its contract was deleted permanently: nothing left to open.
        <div className="card flex flex-col gap-1.5" data-testid="cancellation-review-details">
          <span className="ui-eyebrow">{t("tasks.contractMeta")}</span>
          <span className="text-[15px] leading-snug font-semibold break-words">{review.contractName}</span>
          <span className="text-sm text-text-3" data-testid="cancellation-review-contract-deleted">
            {t("trash.contractDeleted")}
          </span>
        </div>
      )}
      {review && !review.contractDeleted && (
        <Link to={`/contracts/${review.contractId}`} className="card flex flex-col gap-1.5" data-testid="cancellation-review-details">
          <span className="ui-eyebrow">{t("tasks.contractMeta")}</span>
          <span className="text-[15px] leading-snug font-semibold break-words">{review.contractName}</span>
          <span className="ui-meta">
            {review.contractEnd && <span>{t("tasks.cancellation.contractEnd", { date: formatDate(review.contractEnd, i18n.language) })}</span>}
            <span className="text-text-2">{t("tasks.cancellation.deadline", { date: formatDate(review.cancellationDeadline, i18n.language) })}</span>
            <span className={remaining != null && remaining < 0 ? "text-danger" : "text-action"}>
              {remaining != null && remaining < 0
                ? t("tasks.cancellation.overdue", { count: Math.abs(remaining) })
                : t("tasks.cancellation.remaining", { count: remaining ?? 0 })}
            </span>
          </span>
          <span className="text-[13px] leading-relaxed text-text-3">{t("tasks.cancellation.completionHint")}</span>
        </Link>
      )}

      {!task.trash && (open || task.status === "DONE") && (
        <button
          type="button"
          className="btn-primary w-full"
          disabled={busy}
          data-testid={open ? "task-complete" : "task-reopen"}
          onClick={() => {
            if (open) void complete().then((updated) => updated && onCompleted?.(updated));
            else void reopen();
          }}
        >
          {open ? t("tasks.complete") : t("tasks.reopen")}
        </button>
      )}

      {!task.trash && open && (
        <div className="flex flex-wrap gap-2">
          <button type="button" className="btn-ghost" disabled={busy} onClick={() => start("assign", task.ownerId ?? "")} data-testid="task-assign">
            {t("tasks.assign")}
          </button>
          <button type="button" className="btn-ghost" disabled={busy} onClick={() => start("plan", task.plannedDate ?? "")} data-testid="task-plan">
            {t("tasks.plan")}
          </button>
          {!review && (
            <button type="button" className="btn-ghost" disabled={busy} onClick={() => start("due", task.dueDate ?? "")} data-testid="task-change-due">
              {t("tasks.changeDue")}
            </button>
          )}
          <button
            type="button"
            className="btn-ghost"
            disabled={busy}
            onClick={() => {
              start("edit", task.title);
              setDraftNote(task.note ?? "");
            }}
          >
            {t("tasks.edit")}
          </button>
        </div>
      )}

      {editing === "assign" && (
        <form
          className="flex flex-wrap items-end gap-2"
          onSubmit={(event) => {
            event.preventDefault();
            void save(() => api.POST("/api/tasks/{id}/assign", { ...path, body: { ownerId: draft || null, expectedVersion: task.version } }));
          }}
        >
          <label className="min-w-48 flex-1">
            <span className="label">{t("tasks.owner")}</span>
            <select className="field" value={draft} onChange={(event) => setDraft(event.target.value)} data-testid="task-owner-select">
              <option value="">{t("tasks.unassigned")}</option>
              {members.map((member) => (
                <option key={member.id} value={member.id}>
                  {member.displayName}
                </option>
              ))}
            </select>
          </label>
          <button type="submit" className="btn-secondary" disabled={busy}>
            {t("app.save")}
          </button>
          <button type="button" className="btn-ghost" onClick={() => setEditing(null)}>
            {t("app.cancel")}
          </button>
        </form>
      )}
      {(editing === "plan" || editing === "due") && (
        <form
          className="flex flex-wrap items-end gap-2"
          onSubmit={(event) => {
            event.preventDefault();
            const value = draft || null;
            void save(() =>
              editing === "plan"
                ? api.POST("/api/tasks/{id}/plan", { ...path, body: { plannedDate: value, expectedVersion: task.version } })
                : api.POST("/api/tasks/{id}/due-date", { ...path, body: { dueDate: value, expectedVersion: task.version } }),
            );
          }}
        >
          <label className="min-w-48 flex-1">
            <span className="label">{editing === "plan" ? t("tasks.plannedDate") : t("tasks.dueDate")}</span>
            <input type="date" className="field" value={draft} onChange={(event) => setDraft(event.target.value)} data-testid="task-date-input" />
          </label>
          <p className="w-full text-xs text-text-3">{editing === "plan" ? t("tasks.planHint") : t("tasks.dueHint")}</p>
          <button type="submit" className="btn-secondary" disabled={busy}>
            {t("app.save")}
          </button>
          <button type="button" className="btn-ghost" onClick={() => setEditing(null)}>
            {t("app.cancel")}
          </button>
        </form>
      )}
      {editing === "edit" && (
        <form
          className="flex flex-col gap-2"
          onSubmit={(event) => {
            event.preventDefault();
            void save(() => api.PUT("/api/tasks/{id}", { ...path, body: { title: draft, note: draftNote, expectedVersion: task.version } }));
          }}
        >
          <label>
            <span className="label">{t("tasks.titleField")}</span>
            <input className="field" value={draft} onChange={(event) => setDraft(event.target.value)} required maxLength={200} />
          </label>
          <label>
            <span className="label">{t("tasks.note")}</span>
            <textarea className="field" rows={3} value={draftNote} onChange={(event) => setDraftNote(event.target.value)} maxLength={2000} />
          </label>
          <div className="flex gap-2">
            <button type="submit" className="btn-secondary" disabled={busy}>
              {t("app.save")}
            </button>
            <button type="button" className="btn-ghost" onClick={() => setEditing(null)}>
              {t("app.cancel")}
            </button>
          </div>
        </form>
      )}

      {!task.trash && (
        <div className="border-t border-line pt-3">
          <MoveToTrashButton
            busy={busy}
            testId="trash-task"
            onTrash={async () => {
              const trashed = await run(() => api.POST("/api/tasks/{id}/trash", { ...path, body: { expectedVersion: task.version } }));
              if (trashed) onTrashed?.(trashed);
            }}
          />
        </div>
      )}
    </article>
  );
}
