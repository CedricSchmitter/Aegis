import { useCallback, useEffect, useState } from "react";
import { useTranslation } from "react-i18next";
import { api, unwrap } from "../api/client";
import type { AiOverview, AiRun } from "../api/types";
import { useSession } from "../auth/SessionContext";
import { formatDateTime, formatMoney } from "../lib/format";
import { problemMessage } from "../lib/problem";
import { Badge, EmptyState, ErrorBox, InfoBox } from "./common";

const ACTIVE = ["QUEUED", "RUNNING", "WAITING_RETRY"];
const POLL_MS = 3000;

/** A run stopped by today's AI cost limit that continues on the next budget day: no failure (issue #109). */
function waitsForBudgetDay(run: AiRun): boolean {
  return run.state === "WAITING_RETRY" && run.failureCode === "budget.daily" && !!run.nextAttemptAt;
}

type Workload = AiOverview["workloads"][number];
type ModelOption = AiOverview["models"][number];

/**
 * Visible state of the bounded AI execution: configuration, the household's model family per workload, today's
 * spending, the connection check and recent runs with cancel and retry. Capture, search and tasks never depend on
 * this section.
 */
export function AiSection() {
  const { t, i18n } = useTranslation();
  const { memberName } = useSession();
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
  const chooseModel = (workload: Workload, family: ModelOption["family"]) =>
    act(async () => {
      try {
        // The answer is the updated overview, so the dropdown shows the new family before the runs are reloaded.
        setOverview(
          unwrap(
            await api.PUT("/api/ai/workloads/{workload}/model", {
              params: { path: { workload: workload.workload } },
              body: { family, expectedVersion: workload.choiceVersion },
            }),
          ),
        );
      } catch (failure) {
        // After a conflict the other member's choice is shown, so the next attempt uses the current version.
        await load();
        throw failure;
      }
    }, t("ai.model.changed", { workload: t(`ai.workload.${workload.workload}`), family: t(`ai.family.${family}`) }));

  const money = (amount: number | undefined) => formatMoney(amount ?? 0, overview?.currency ?? "USD", i18n.language);

  return (
    <section aria-labelledby="ai" className="flex flex-col gap-2" data-testid="ai-section">
      <div className="flex flex-wrap items-center justify-between gap-2">
        <h3 id="ai" className="ui-sec">
          {t("ai.title")}
        </h3>
        <button type="button" className="btn-secondary" onClick={checkConnection} disabled={busy || !overview?.configured || !overview.memberAllowed} data-testid="ai-check">
          {t("ai.checkConnection")}
        </button>
      </div>
      <ErrorBox message={error} />
      {message && <InfoBox>{message}</InfoBox>}
      {overview && (
        <div className="card flex flex-col gap-2 text-sm text-text-2">
          <p>{overview.configured ? t("ai.configured") : t("ai.notConfigured")}</p>
          {overview.configured && !overview.memberAllowed && <p data-testid="ai-member-not-allowed">{t("ai.memberNotAllowed")}</p>}
          <p>
            {t("ai.spentToday", { spent: money(overview.spentToday), limit: money(overview.dailyCostLimit) })}
            {overview.limitProvider && ` (${t(`ai.provider.${overview.limitProvider}`, { defaultValue: overview.limitProvider })})`}
          </p>
          <p className="text-xs text-text-3">{t("ai.dailyLimitHint")}</p>
          <ul className="flex flex-col gap-3">
            {overview.workloads.map((workload) => (
              <li key={workload.workload} className="flex flex-col gap-1">
                <div className="flex flex-wrap items-center justify-between gap-2">
                  <span>
                    <span className="font-medium text-text-1">{t(`ai.workload.${workload.workload}`)}</span>
                    {workload.model && (
                      <span className="text-text-3">
                        {" "}
                        · {workload.model}
                        {workload.provider && ` · ${t(`ai.provider.${workload.provider}`, { defaultValue: workload.provider })}`}
                      </span>
                    )}
                    <span className="text-text-3">
                      {" "}
                      · {t("ai.budgetLine", { cost: money(workload.maxCost), attempts: workload.maxAttempts, seconds: workload.maxDurationSeconds })}
                    </span>
                  </span>
                  <span className="font-mono text-xs text-text-3">
                    {workload.available ? t("ai.available") : !workload.handlerAvailable ? t("ai.noWorkflow") : t("ai.unavailable")}
                  </span>
                </div>
                {workload.family && overview.models.length > 0 && (
                  <ModelChoice
                    workload={workload}
                    options={overview.models}
                    disabled={busy}
                    currency={overview.currency}
                    changedBy={memberName(workload.choiceChangedBy)}
                    onChoose={(family) => void chooseModel(workload, family)}
                  />
                )}
              </li>
            ))}
          </ul>
          {overview.models.length > 0 && <p className="text-xs text-text-3">{t("ai.model.scope")}</p>}
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
                {waitsForBudgetDay(run) ? (
                  <Badge state="WAITING_FOR_BUDGET" label={t("ai.budgetWait")} />
                ) : (
                  <Badge state={run.state} label={t(`ai.state.${run.state}`)} />
                )}
              </div>
              <p className="text-text-3">
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
              {waitsForBudgetDay(run) ? (
                <p className="text-text-3" data-testid="ai-run-waiting-for-budget">
                  {t("ai.waitingForBudget", { date: formatDateTime(run.nextAttemptAt ?? "", i18n.language) })}
                </p>
              ) : run.failureCategory && (
                <p className={run.state === "FAILED" ? "text-danger" : "text-action"}>
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

/** Model family of one workload for the whole household, with the API model and prices behind each choice. */
function ModelChoice({
  workload,
  options,
  disabled,
  currency,
  changedBy,
  onChoose,
}: {
  workload: Workload;
  options: ModelOption[];
  disabled: boolean;
  currency: string;
  changedBy: string | null;
  onChoose: (family: ModelOption["family"]) => void;
}) {
  const { t, i18n } = useTranslation();
  const id = `ai-model-${workload.workload}`;
  const selected = options.find((option) => option.family === workload.family);
  const price = (amount: number | null | undefined) => (amount == null ? "–" : formatMoney(amount, currency, i18n.language));
  return (
    <div className="flex flex-wrap items-end gap-x-3 gap-y-1">
      <div className="w-full max-w-60">
        <label className="label" htmlFor={id}>
          {t("ai.model.label")}
        </label>
        <select
          id={id}
          className="field"
          value={workload.family ?? ""}
          disabled={disabled}
          data-testid={id}
          onChange={(e) => onChoose(e.target.value as ModelOption["family"])}
        >
          {options.map((option) => (
            <option key={option.family} value={option.family}>
              {t(`ai.family.${option.family}`)}
            </option>
          ))}
        </select>
      </div>
      <p className="text-xs text-text-3">
        {selected && t("ai.model.details", { model: selected.model, input: price(selected.inputPerMillion), output: price(selected.outputPerMillion) })}
        {changedBy && workload.choiceChangedAt && (
          <>
            <br />
            {t("ai.model.changedBy", { name: changedBy, date: formatDateTime(workload.choiceChangedAt, i18n.language) })}
          </>
        )}
      </p>
    </div>
  );
}
