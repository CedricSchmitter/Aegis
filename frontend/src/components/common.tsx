import type { ReactNode } from "react";
import { useTranslation } from "react-i18next";

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
    <p className="py-6 text-center text-stone-500" aria-busy="true">
      {t("app.loading")}
    </p>
  );
}

export function EmptyState({ children }: { children: ReactNode }) {
  return <p className="card text-stone-600 dark:text-stone-400">{children}</p>;
}

const STATE_STYLES: Record<string, string> = {
  RECEIVED: "bg-amber-100 text-amber-900 dark:bg-amber-900 dark:text-amber-100",
  SUBMITTED: "bg-sky-100 text-sky-900 dark:bg-sky-900 dark:text-sky-100",
  ARCHIVED: "bg-emerald-100 text-emerald-900 dark:bg-emerald-900 dark:text-emerald-100",
  FAILED: "bg-red-100 text-red-900 dark:bg-red-900 dark:text-red-100",
  CONNECTED: "bg-emerald-100 text-emerald-900 dark:bg-emerald-900 dark:text-emerald-100",
  DEGRADED: "bg-amber-100 text-amber-900 dark:bg-amber-900 dark:text-amber-100",
  UNAVAILABLE: "bg-red-100 text-red-900 dark:bg-red-900 dark:text-red-100",
  NOT_CONFIGURED: "bg-stone-200 text-stone-800 dark:bg-stone-800 dark:text-stone-200",
  QUEUED: "bg-stone-200 text-stone-800 dark:bg-stone-800 dark:text-stone-200",
  RUNNING: "bg-sky-100 text-sky-900 dark:bg-sky-900 dark:text-sky-100",
  WAITING_RETRY: "bg-amber-100 text-amber-900 dark:bg-amber-900 dark:text-amber-100",
  SUCCEEDED: "bg-emerald-100 text-emerald-900 dark:bg-emerald-900 dark:text-emerald-100",
  CANCELLED: "bg-stone-200 text-stone-800 dark:bg-stone-800 dark:text-stone-200",
  NOT_REQUESTED: "bg-stone-200 text-stone-800 dark:bg-stone-800 dark:text-stone-200",
  WAITING_FOR_ARCHIVE: "bg-stone-200 text-stone-800 dark:bg-stone-800 dark:text-stone-200",
  PENDING: "bg-sky-100 text-sky-900 dark:bg-sky-900 dark:text-sky-100",
  CLASSIFIED: "bg-emerald-100 text-emerald-900 dark:bg-emerald-900 dark:text-emerald-100",
  NEEDS_REVIEW: "bg-violet-100 text-violet-900 dark:bg-violet-900 dark:text-violet-100",
  OPEN: "bg-sky-100 text-sky-900 dark:bg-sky-900 dark:text-sky-100",
  DONE: "bg-emerald-100 text-emerald-900 dark:bg-emerald-900 dark:text-emerald-100",
  ACTIVE: "bg-sky-100 text-sky-900 dark:bg-sky-900 dark:text-sky-100",
  ENDED: "bg-stone-200 text-stone-800 dark:bg-stone-800 dark:text-stone-200",
  EXTRACTED: "bg-violet-100 text-violet-900 dark:bg-violet-900 dark:text-violet-100",
  CONFIRMED: "bg-emerald-100 text-emerald-900 dark:bg-emerald-900 dark:text-emerald-100",
  REJECTED: "bg-red-100 text-red-900 dark:bg-red-900 dark:text-red-100",
  SUPERSEDED: "bg-stone-200 text-stone-800 dark:bg-stone-800 dark:text-stone-200",
  TEXT_NONE: "bg-amber-100 text-amber-900 dark:bg-amber-900 dark:text-amber-100",
  TEXT_LITTLE: "bg-amber-100 text-amber-900 dark:bg-amber-900 dark:text-amber-100",
  JOB_OK: "bg-emerald-100 text-emerald-900 dark:bg-emerald-900 dark:text-emerald-100",
  JOB_PENDING: "bg-sky-100 text-sky-900 dark:bg-sky-900 dark:text-sky-100",
  JOB_FAILING: "bg-amber-100 text-amber-900 dark:bg-amber-900 dark:text-amber-100",
  JOB_STALE: "bg-red-100 text-red-900 dark:bg-red-900 dark:text-red-100",
  JOB_NOT_SCHEDULED: "bg-red-100 text-red-900 dark:bg-red-900 dark:text-red-100",
  JOB_UNKNOWN: "bg-stone-200 text-stone-800 dark:bg-stone-800 dark:text-stone-200",
};

export function Badge({ state, label }: { state: string; label: string }) {
  return (
    <span className={`badge ${STATE_STYLES[state] ?? STATE_STYLES.NOT_CONFIGURED}`} data-state={state}>
      {label}
    </span>
  );
}
