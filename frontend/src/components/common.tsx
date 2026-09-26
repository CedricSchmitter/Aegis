import type { ReactNode } from "react";
import { useTranslation } from "react-i18next";
import { Badge as StatusBadge, type Tone } from "./ui";

export function PageTitle({ children, actions }: { children: ReactNode; actions?: ReactNode }) {
  return (
    <div className="mb-4 flex flex-wrap items-center justify-between gap-2">
      <h1 className="text-2xl font-semibold">{children}</h1>
      {actions}
    </div>
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
  REJECTED: "danger",
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

export function Badge({ state, label }: { state: string; label: string }) {
  return (
    <StatusBadge tone={STATE_TONES[state] ?? "neutral"} data-state={state}>
      {label}
    </StatusBadge>
  );
}
