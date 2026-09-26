import type { MouseEvent } from "react";
import { Link, useNavigate } from "react-router";
import { useTranslation } from "react-i18next";
import type { TaskDto } from "../../api/types";
import { useSession } from "../../auth/SessionContext";
import { formatShortDate, todayIso } from "../../lib/format";
import { initial, isOverdue } from "../../lib/tasks";
import { Badge } from "../common";
import { cardClass } from "../ui";
import { useTaskMutation } from "./useTaskMutation";

/** The title a task is shown with; a cancellation review names its contract. */
export function useTaskTitle(task: TaskDto): string {
  const { t } = useTranslation();
  const review = task.cancellationReview;
  return review && task.title === review.contractName ? t("tasks.cancellation.title", { name: review.contractName }) : task.title;
}

/** The responsible member as an initial in a small box, or "Nobody". */
export function OwnerMark({ ownerId }: { ownerId: string | null | undefined }) {
  const { t } = useTranslation();
  const { memberName } = useSession();
  const name = memberName(ownerId);
  if (!name) return <span className="font-mono text-xs whitespace-nowrap text-text-3">{t("tasks.nobodyShort")}</span>;
  return (
    <span className="owner-mark" title={name}>
      <span aria-hidden="true">{initial(name)}</span>
      <span className="sr-only">{t("tasks.ownerLabel", { name })}</span>
    </span>
  );
}

/** The due date and the planned work day, kept apart: "Frist" in text colour, overdue in red, "Geplant" in data. */
export function TaskMeta({ task }: { task: TaskDto }) {
  const { t, i18n } = useTranslation();
  const { memberName } = useSession();
  const today = todayIso();
  const short = (date: string) => (date === today ? t("tasks.todayWord") : formatShortDate(date, i18n.language, today));
  return (
    <p className="ui-meta">
      {task.status === "DONE" ? (
        <span>
          {t("tasks.doneBy", {
            name: memberName(task.completedBy) ?? t("app.unknownMember"),
            date: formatShortDate(task.completedAt?.substring(0, 10), i18n.language, today),
          })}
        </span>
      ) : (
        <>
          {task.dueDate &&
            (isOverdue(task, today) ? (
              <span className="text-danger" data-state="OVERDUE">
                {t("tasks.overdueBy", { date: formatShortDate(task.dueDate, i18n.language, today) })}
              </span>
            ) : (
              <span className="text-text-2">{t("tasks.due", { date: short(task.dueDate) })}</span>
            ))}
          {task.plannedDate && <span className="text-data">{t("tasks.planned", { date: short(task.plannedDate) })}</span>}
        </>
      )}
      {task.source && (
        <span className="min-w-0 break-words">
          {task.source.documentTitle ? t("tasks.source", { title: task.source.documentTitle }) : t("tasks.sourceMissing")}
        </span>
      )}
      {task.cancellationReview && <span>{t("tasks.contractMeta")}</span>}
    </p>
  );
}

/** Whether a click is a plain single mouse click, which selects instead of opening (issue #27). */
export function isSelectClick(event: MouseEvent<HTMLAnchorElement>): boolean {
  return event.detail > 0 && event.button === 0 && !event.ctrlKey && !event.metaKey && !event.shiftKey && !event.altKey;
}

interface Props {
  task: TaskDto;
  onChange(task: TaskDto): void;
  /** After the checkbox completed the task, for the message with undo. */
  onCompleted?(task: TaskDto): void;
  /** Shown in the context panel; a click then selects instead of opening the task. */
  selected?: boolean;
  onSelect?: ((id: string) => void) | null;
}

/**
 * A task in a list (issue #27): the checkbox completes it at once, the rest of the card opens it. An overdue task has
 * a red frame; the task open in the context panel has the action frame.
 */
export function TaskCard({ task, onChange, onCompleted, selected = false, onSelect = null }: Props) {
  const { t } = useTranslation();
  const navigate = useNavigate();
  const title = useTaskTitle(task);
  const { busy, error, conflict, complete, reopen } = useTaskMutation(task, onChange);
  const done = task.status === "DONE";
  const toggleable = !task.trash && (task.status === "OPEN" || done);
  const overdue = isOverdue(task, todayIso());

  return (
    <li
      className={cardClass(overdue ? "danger" : undefined, selected, "flex flex-col gap-2")}
      data-testid="task-item"
      data-task-id={task.id}
      data-selected={onSelect ? selected : undefined}
      onDoubleClick={(event) => {
        if (!onSelect || (event.target as HTMLElement).closest("input, label")) return;
        navigate(`/tasks/${task.id}`);
      }}
    >
      <div className="grid grid-cols-[auto_minmax(0,1fr)] items-start gap-3">
        <label className="-m-2 grid h-11 w-11 cursor-pointer place-items-center" data-above>
          <input
            type="checkbox"
            className="task-check"
            checked={done}
            disabled={busy || !toggleable}
            aria-label={done ? t("tasks.checkReopen", { title }) : t("tasks.checkComplete", { title })}
            data-testid="task-check"
            onChange={() => {
              if (done) {
                void reopen();
                return;
              }
              void complete().then((updated) => updated && onCompleted?.(updated));
            }}
          />
        </label>
        <div className="flex min-w-0 flex-col gap-1.5">
          <div className="flex items-start gap-2.5">
            <h3 className={`min-w-0 flex-1 text-[15px] leading-snug font-semibold break-words ${done ? "text-text-4 line-through" : ""}`}>
              <Link
                to={`/tasks/${task.id}`}
                className="ui-stretch"
                aria-current={selected && onSelect ? "true" : undefined}
                onClick={(event) => {
                  if (onSelect && isSelectClick(event)) {
                    event.preventDefault();
                    onSelect(task.id);
                  }
                }}
              >
                {title}
              </Link>
            </h3>
            <OwnerMark ownerId={task.ownerId} />
          </div>
          <TaskMeta task={task} />
          {task.status === "CANCELLED" && (
            <span>
              <Badge state="CANCELLED" label={t("tasks.status.CANCELLED")} />
            </span>
          )}
        </div>
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
    </li>
  );
}
