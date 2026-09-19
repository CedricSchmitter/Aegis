import { useCallback, useEffect, useState } from "react";
import { useTranslation } from "react-i18next";
import { api, unwrap } from "../api/client";
import type { AiOverview, AiRun } from "../api/types";
import { formatDateTime, formatMoney } from "../lib/format";
import { problemMessage } from "../lib/problem";
import { Badge, EmptyState, ErrorBox, InfoBox } from "./common";

const ACTIVE = ["QUEUED", "RUNNING", "WAITING_RETRY"];
const POLL_MS = 3000;

/**
 * Visible state of the bounded AI execution: configuration, today's spending, the connection check and recent runs
 * with cancel and retry. Capture, search and tasks never depend on this section.
 */
export function AiSection() {
  const { t, i18n } = useTranslation();
  const [overview, setOverview] = useState<AiOverview | null>(null);
  const [runs, setRuns] = useState<AiRun[]>([]);
  const [error, setError] = useState<string | null>(null);
  const [message, setMessage] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  const load = useCallback(async () => {
    try {
      const [o, r] = await Promise.all([api.GET("/api/ai/overview"), api.GET("/api/ai/runs", { params: { query: { limit: 10 } } })]);
      setOverview(unwrap(o));
      setRuns(unwrap(r));
      setError(null);
    } catch (failure) {
      setError(problemMessage(t, failure));
    }
  }, [t]);

  useEffect(() => {
    void load();
  }, [load]);

  // Waiting retries have a known next attempt; only queued and running runs are followed closely.
  const anyActive = runs.some((run) => run.state === "QUEUED" || run.state === "RUNNING");
  useEffect(() => {
    if (!anyActive) return;
    const timer = window.setInterval(() => void load(), POLL_MS);
    return () => window.clearInterval(timer);
  }, [anyActive, load]);

  const act = async (action: () => Promise<unknown>, done: string) => {
    setBusy(true);
    setMessage(null);
    try {
      await action();
      setMessage(done);
      await load();
    } catch (failure) {
      setError(problemMessage(t, failure));
    } finally {
      setBusy(false);
    }
  };

  const checkConnection = () =>
    act(async () => unwrap(await api.POST("/api/ai/connection-checks")), t("ai.checkStarted"));
  const cancel = (id: string) =>
    act(async () => unwrap(await api.POST("/api/ai/runs/{id}/cancel", { params: { path: { id } } })), t("ai.cancelRequested"));
  const retry = (id: string) =>
    act(async () => unwrap(await api.POST("/api/ai/runs/{id}/retry", { params: { path: { id } } })), t("ai.retryStarted"));

  const money = (amount: number | undefined) => formatMoney(amount ?? 0, overview?.currency ?? "USD", i18n.language);

  return (
    <section aria-labelledby="ai" data-testid="ai-section">
      <div className="mb-2 flex flex-wrap items-center justify-between gap-2">
        <h2 id="ai" className="text-lg font-semibold">
          {t("ai.title")}
        </h2>
        <button type="button" className="btn-secondary" onClick={checkConnection} disabled={busy || !overview?.configured || !overview.memberAllowed} data-testid="ai-check">
          {t("ai.checkConnection")}
        </button>
      </div>
      <ErrorBox message={error} />
      {message && <InfoBox>{message}</InfoBox>}
      {overview && (
        <div className="card mb-2 flex flex-col gap-2 text-sm">
          <p>{overview.configured ? t("ai.configured") : t("ai.notConfigured")}</p>
          {overview.configured && !overview.memberAllowed && <p data-testid="ai-member-not-allowed">{t("ai.memberNotAllowed")}</p>}
          <p>
            {t("ai.spentToday", { spent: money(overview.spentToday), limit: money(overview.dailyCostLimit) })}
            {overview.limitProvider && ` (${t(`ai.provider.${overview.limitProvider}`, { defaultValue: overview.limitProvider })})`}
          </p>
          <ul className="flex flex-col gap-1">
            {overview.workloads.map((workload) => (
              <li key={workload.workload} className="flex flex-wrap items-center justify-between gap-2">
                <span>
                  <span className="font-medium">{t(`ai.workload.${workload.workload}`)}</span>
                  {workload.model && (
                    <span className="text-stone-600 dark:text-stone-400">
                      {" "}
                      · {workload.model}
                      {workload.provider && ` · ${t(`ai.provider.${workload.provider}`, { defaultValue: workload.provider })}`}
                    </span>
                  )}
                  <span className="text-stone-600 dark:text-stone-400">
                    {" "}
                    · {t("ai.budgetLine", { cost: money(workload.maxCost), attempts: workload.maxAttempts, seconds: workload.maxDurationSeconds })}
                  </span>
                </span>
                <span className="text-xs text-stone-600 dark:text-stone-400">
                  {workload.available ? t("ai.available") : !workload.handlerAvailable ? t("ai.noWorkflow") : t("ai.unavailable")}
                </span>
              </li>
            ))}
          </ul>
        </div>
      )}
      {overview && runs.length === 0 && <EmptyState>{t("ai.noRuns")}</EmptyState>}
      {runs.length > 0 && (
        <ul className="flex flex-col gap-2" aria-live="polite">
          {runs.map((run) => (
            <li key={run.id} className="card flex flex-col gap-1 text-sm" data-testid={`ai-run-${run.id}`}>
              <div className="flex flex-wrap items-center justify-between gap-2">
                <span className="font-medium">
                  {t(`ai.workload.${run.workload}`)} · {run.model}
                </span>
                <Badge state={run.state} label={t(`ai.state.${run.state}`)} />
              </div>
              <p className="text-stone-600 dark:text-stone-400">
                {t("ai.runLine", {
                  date: formatDateTime(run.requestedAt, i18n.language),
                  attempt: run.attempt,
                  maxAttempts: run.maxAttempts,
                  cost: money(run.cost),
                  limit: money(run.maxCost),
                  calls: run.modelCalls,
                  tools: run.toolCalls,
                })}
              </p>
              {run.cancelRequested && run.state === "RUNNING" && <p>{t("ai.cancelPending")}</p>}
              {run.failureCategory && (
                <p className={run.state === "FAILED" ? "text-red-700 dark:text-red-400" : "text-amber-700 dark:text-amber-400"}>
                  {t(`ai.failure.${run.failureCategory}`)} ({run.failureCode})
                  {run.nextAttemptAt && run.state === "WAITING_RETRY" && <> · {t("ai.nextAttempt", { date: formatDateTime(run.nextAttemptAt, i18n.language) })}</>}
                </p>
              )}
              <div className="flex gap-2">
                {ACTIVE.includes(run.state) && !run.cancelRequested && (
                  <button type="button" className="btn-secondary" onClick={() => cancel(run.id)} disabled={busy}>
                    {t("ai.cancel")}
                  </button>
                )}
                {(run.state === "FAILED" || run.state === "CANCELLED") && (
                  <button type="button" className="btn-secondary" onClick={() => retry(run.id)} disabled={busy}>
                    {t("ai.retry")}
                  </button>
                )}
              </div>
            </li>
          ))}
        </ul>
      )}
    </section>
  );
}
