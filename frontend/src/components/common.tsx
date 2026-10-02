import type { ReactNode } from "react";
import { Link } from "react-router";
import { useTranslation } from "react-i18next";
import { Icon } from "./shell/icons";
import { Badge as StatusBadge, type Tone } from "./ui";

export function PageTitle({ children, actions }: { children: ReactNode; actions?: ReactNode }) {
  return (
    <div className="mb-4 flex flex-wrap items-center justify-between gap-2">
      <h1 className="text-2xl font-semibold">{children}</h1>
      {actions}
    </div>
  );
}

/** The big uppercase page title, with an optional eyebrow and count. */
export function BigTitle({ children, count, eyebrow }: { children: ReactNode; count?: ReactNode; eyebrow?: ReactNode }) {
  return (
    <div className="mb-4 flex flex-col gap-1.5">
      {eyebrow && <p className="ui-eyebrow">{eyebrow}</p>}
      <div className="flex items-end justify-between gap-3">
        <h1 className="text-[32px] leading-[0.95] font-bold tracking-[0.04em] break-words uppercase">{children}</h1>
        {count && <span className="pb-0.5 font-mono text-[13px] whitespace-nowrap text-text-3">{count}</span>}
      </div>
    </div>
  );
}

/** The quiet mono link back to a list. */
export function BackLink({ to, children }: { to: string; children: ReactNode }) {
  return (
    <Link
      to={to}
      className="-ml-1 flex min-h-11 items-center gap-1.5 self-start font-mono text-xs tracking-[0.18em] text-text-3 uppercase hover:text-text-1"
    >
      <Icon name="back" className="h-4 w-4" />
      {children}
    </Link>
  );
}

export function ErrorBox({ message }: { message: string | null }) {
  if (!message) return null;
  return (
    <p className="alert-error" role="alert">
      {message}
    </p>
  );
}

export function InfoBox({ children }: { children: ReactNode }) {
  return (
    <p className="alert-info" role="status">
      {children}
    </p>
  );
}

export function Loading() {
  const { t } = useTranslation();
  return (
    <p className="py-6 text-center text-text-3" aria-busy="true">
      {t("app.loading")}
    </p>
  );
}

export function EmptyState({ children }: { children: ReactNode }) {
  return <p className="card text-text-3">{children}</p>;
}

// Colour semantics (ADR-0019): attention asks for action, data is a normal or finished state, danger is anything
// broken or failed, neutral is grey.
const STATE_TONES: Record<string, Tone> = {
  RECEIVED: "attention",
  SUBMITTED: "data",
  ARCHIVED: "data",
  FAILED: "danger",
  UNAVAILABLE: "danger",
  NOT_CONFIGURED: "neutral",
  QUEUED: "neutral",
  RUNNING: "data",
  WAITING_RETRY: "attention",
  SUCCEEDED: "data",
  CANCELLED: "neutral",
  NOT_REQUESTED: "neutral",
  // Requested for a member who may not use AI: an expected state, not a failure.
  NOT_CLASSIFIED: "neutral",
  // Today's AI cost limit is used up and the run continues on the next budget day: expected, not a failure.
  WAITING_FOR_BUDGET: "neutral",
  WAITING_FOR_ARCHIVE: "neutral",
  PENDING: "data",
  CLASSIFIED: "data",
  NEEDS_REVIEW: "attention",
  OPEN: "data",
  DONE: "data",
  ACTIVE: "data",
  ENDED: "neutral",
  EXTRACTED: "attention",
  CONFIRMED: "data",
  // A rejected contract statement is history a member decided on, not something broken.
  REJECTED: "neutral",
  SUPERSEDED: "neutral",
  TEXT_NONE: "attention",
  TEXT_LITTLE: "attention",
  CHECK_OK: "data",
  CHECK_UNCHECKED: "neutral",
  CHECK_PROBLEM: "danger",
  JOB_OK: "data",
  JOB_PENDING: "data",
  JOB_FAILING: "danger",
  JOB_STALE: "danger",
  JOB_NOT_SCHEDULED: "danger",
  JOB_UNKNOWN: "neutral",
  BACKUP_OK: "data",
  BACKUP_RUNNING: "data",
  BACKUP_FAILED: "danger",
  BACKUP_STALE: "danger",
  BACKUP_INTERRUPTED: "attention",
  BACKUP_NOT_CONFIGURED: "neutral",
  BACKUP_UNKNOWN: "neutral",
  DISK_OK: "data",
  DISK_LOW: "danger",
  DISK_UNKNOWN: "neutral",
};

export function stateTone(state: string): Tone {
  return STATE_TONES[state] ?? "neutral";
}

export function Badge({ state, label }: { state: string; label: string }) {
  return (
    <StatusBadge tone={stateTone(state)} data-state={state}>
      {label}
    </StatusBadge>
  );
}

/** The state of a status or job row as a mono word without a frame; the row itself is the frame. */
export function StateWord({ state, label }: { state: string; label: string }) {
  return (
    <span className="ui-state" data-state={state} data-tone={stateTone(state)}>
      {label}
    </span>
  );
}
