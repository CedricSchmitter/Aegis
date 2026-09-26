import { useCallback, useEffect, useState } from "react";
import { useTranslation } from "react-i18next";
import { api, unwrap } from "../api/client";
import type { ExportSummary, HealthCheck, SystemStatus, VersionResponse } from "../api/types";
import { AiSection } from "../components/AiSection";
import { Badge, EmptyState, ErrorBox, InfoBox, Loading, PageTitle } from "../components/common";
import { StorageSection } from "../components/StorageSection";
import { formatDateTime } from "../lib/format";
import { jobInterval } from "../lib/jobs";
import { problemMessage } from "../lib/problem";
import { areaLabel, checkHint, checkText, jobName, lastOkText, problemCount, stateLabel } from "../lib/status";

/**
 * The status page answers "is everything OK?" first (issue #83, F4); what only the owner needs is collapsed under
 * technical details.
 */
export function StatusPage() {
  const { t } = useTranslation();
  const [status, setStatus] = useState<SystemStatus | null>(null);
  const [exports, setExports] = useState<ExportSummary[] | null>(null);
  const [version, setVersion] = useState<VersionResponse | null>(null);
  const [error, setError] = useState<string | null>(null);

  const load = useCallback(async () => {
    try {
      const [s, e, v] = await Promise.all([api.GET("/api/status"), api.GET("/api/exports"), api.GET("/api/system/version")]);
      setStatus(unwrap(s));
      setExports(unwrap(e));
      setVersion(unwrap(v));
      setError(null);
    } catch (failure) {
      setError(problemMessage(t, failure));
    }
  }, [t]);

  useEffect(() => {
    void load();
  }, [load]);

  return (
    <>
      <PageTitle>{t("status.title")}</PageTitle>
      <ErrorBox message={error} />
      {!status && !error && <Loading />}
      {status && (
        <div className="flex flex-col gap-4">
          <Overview checks={status.checks} />
          <details className="flex flex-col gap-4" data-testid="technical-details">
            <summary className="cursor-pointer">
              <h2 className="inline text-lg font-semibold">{t("status.technicalDetails")}</h2>
            </summary>
            <div className="mt-3 flex flex-col gap-4">
              <Processing counts={status.documentProcessing} />
              <StorageSection storage={status.storage} />
              <Jobs jobs={status.jobs} />
              <AiSection />
              <Exports exports={exports} onCreated={load} onError={setError} />
              {version && <p className="text-xs text-stone-500">{t("status.version", { commit: version.commit, time: version.buildTime })}</p>}
            </div>
          </details>
        </div>
      )}
    </>
  );
}

function Overview({ checks }: { checks: HealthCheck[] }) {
  const { t, i18n } = useTranslation();
  const problems = problemCount(checks);
  return (
    <section aria-labelledby="overview" data-testid="status-overview">
      <h2 id="overview" className="mb-1 text-lg font-semibold">
        {t("status.overview")}
      </h2>
      <p className={`mb-2 ${problems > 0 ? "alert-warn" : "text-sm text-stone-600 dark:text-stone-400"}`} role="status" data-testid="overview-summary">
        {problems > 0 ? t("status.overviewProblems", { count: problems }) : t("status.overviewOk")}
      </p>
      <ul className="flex flex-col gap-2">
        {checks.map((check) => {
          const hint = check.state === "PROBLEM" ? checkHint(t, check) : null;
          const lastOk = lastOkText(t, check, i18n.language);
          return (
            <li key={check.area} className="card flex flex-wrap items-start justify-between gap-2" data-testid={`check-${check.area}`}>
              <div className="min-w-0 flex-1">
                <p className="font-medium">{areaLabel(t, check.area)}</p>
                <p className="text-sm text-stone-600 dark:text-stone-400">{checkText(t, check, i18n.language)}</p>
                {hint && <p className="text-sm text-red-700 dark:text-red-300">{hint}</p>}
                {lastOk && <p className="text-xs text-stone-500">{lastOk}</p>}
              </div>
              <Badge state={`CHECK_${check.state}`} label={stateLabel(t, check.state)} />
            </li>
          );
        })}
      </ul>
    </section>
  );
}

function Processing({ counts }: { counts: SystemStatus["documentProcessing"] }) {
  const { t } = useTranslation();
  return (
    <section aria-labelledby="processing">
      <h3 id="processing" className="mb-2 font-semibold">
        {t("status.processing")}
      </h3>
      <ul className="card grid grid-cols-2 gap-2 sm:grid-cols-4">
        {Object.entries(counts ?? {}).map(([state, count]) => (
          <li key={state} className="flex flex-col">
            <span className="text-2xl font-semibold">{count}</span>
            <span className="text-sm text-stone-600 dark:text-stone-400">{String(t(`documents.stateShort.${state}`, { defaultValue: state }))}</span>
          </li>
        ))}
      </ul>
    </section>
  );
}

function Jobs({ jobs }: { jobs: SystemStatus["jobs"] }) {
  const { t, i18n } = useTranslation();
  return (
    <section aria-labelledby="jobs">
      <h3 id="jobs" className="mb-1 font-semibold">
        {t("status.jobs.title")}
      </h3>
      <p className="mb-2 text-sm text-stone-600 dark:text-stone-400">{t("status.jobs.intro")}</p>
      <ul className="flex flex-col gap-2">
        {jobs.map((job) => (
          <li key={job.name} className="card flex flex-wrap items-center justify-between gap-2" data-testid={`job-${job.name}`}>
            <div className="min-w-0">
              <p className="font-medium">{jobName(t, job.name)}</p>
              <p className="text-sm text-stone-600 dark:text-stone-400">
                {i18n.exists(`status.jobs.purpose.${job.name}`) && <>{t(`status.jobs.purpose.${job.name}`)} </>}
                {t("status.jobs.runs", { interval: jobInterval(t, job.intervalSeconds) })}
              </p>
              <p className="text-xs text-stone-500">
                {t("status.lastSuccess", { date: job.lastSuccessAt ? formatDateTime(job.lastSuccessAt, i18n.language) : t("status.never") })}
                {job.nextRunAt && <> · {t("status.jobs.nextRun", { date: formatDateTime(job.nextRunAt, i18n.language) })}</>}
              </p>
              {job.consecutiveFailures > 0 && job.lastFailureAt && (
                <p className="text-xs text-red-700 dark:text-red-300">
                  {t("status.jobs.lastFailure", { date: formatDateTime(job.lastFailureAt, i18n.language), count: job.consecutiveFailures })}
                </p>
              )}
            </div>
            <Badge state={`JOB_${job.state}`} label={t(`status.jobs.state.${job.state}`)} />
          </li>
        ))}
      </ul>
    </section>
  );
}

function Exports({ exports, onCreated, onError }: { exports: ExportSummary[] | null; onCreated: () => Promise<void>; onError: (message: string) => void }) {
  const { t } = useTranslation();
  const [message, setMessage] = useState<string | null>(null);
  const [exporting, setExporting] = useState(false);

  const createExport = async () => {
    setExporting(true);
    setMessage(null);
    try {
      const created = unwrap(await api.POST("/api/exports"));
      setMessage(t("status.exportCreated", { generation: created.generation }));
      await onCreated();
    } catch (failure) {
      onError(problemMessage(t, failure));
    } finally {
      setExporting(false);
    }
  };

  return (
    <section aria-labelledby="exports">
      <div className="mb-2 flex items-center justify-between gap-2">
        <h3 id="exports" className="font-semibold">
          {t("status.exports")}
        </h3>
        <button type="button" className="btn-primary" onClick={createExport} disabled={exporting} data-testid="create-export">
          {exporting ? t("status.exporting") : t("status.createExport")}
        </button>
      </div>
      {message && <InfoBox>{message}</InfoBox>}
      {exports && exports.length === 0 && <EmptyState>{t("status.noExports")}</EmptyState>}
      {exports && exports.length > 0 && (
        <ul className="card flex flex-col gap-1 text-sm">
          {exports.map((e) => (
            <li key={e.generation}>
              {t("status.exportLine", { generation: e.generation, documents: e.documentCount, tasks: e.taskCount })}
              {e.missingOriginals.length > 0 && <span className="text-amber-700"> · {t("status.missingOriginals", { count: e.missingOriginals.length })}</span>}
            </li>
          ))}
        </ul>
      )}
    </section>
  );
}
