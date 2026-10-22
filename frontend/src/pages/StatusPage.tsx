import { useCallback, useEffect, useState } from "react";
import { useTranslation } from "react-i18next";
import { api, unwrap } from "../api/client";
import type { ExportSummary, SystemStatus, VersionResponse } from "../api/types";
import { AiSection } from "../components/AiSection";
import { BigTitle, EmptyState, ErrorBox, InfoBox, Loading, StateWord } from "../components/common";
import { StatusOverview } from "../components/status/StatusOverview";
import { StorageSection } from "../components/StorageSection";
import { formatDateTime } from "../lib/format";
import { jobInterval } from "../lib/jobs";
import { problemMessage } from "../lib/problem";
import { jobName } from "../lib/status";

/**
 * The status page answers "is everything OK?" first: broken areas are red with their reason and
 * what to do now. What only helps when looking for a fault is collapsed under technical details.
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
    <div className="flex max-w-2xl flex-col">
      <BigTitle>{t("status.title")}</BigTitle>
      <ErrorBox message={error} />
      {!status && !error && <Loading />}
      {status && (
        <div className="flex flex-col gap-5">
          <StatusOverview checks={status.checks} />
          <details className="ui-details" data-testid="technical-details">
            <summary>{t("status.technicalDetails")}</summary>
            <div className="flex flex-col gap-7 pt-2 pb-4">
              <Processing counts={status.documentProcessing} />
              <StorageSection storage={status.storage} />
              <Jobs jobs={status.jobs} />
              <AiSection />
              <Exports exports={exports} onCreated={load} onError={setError} />
              {version && (
                <p className="font-mono text-xs text-text-4">{t("status.version", { commit: version.commit, time: version.buildTime })}</p>
              )}
            </div>
          </details>
        </div>
      )}
    </div>
  );
}

function Processing({ counts }: { counts: SystemStatus["documentProcessing"] }) {
  const { t } = useTranslation();
  return (
    <section aria-labelledby="processing" className="flex flex-col gap-2">
      <h3 id="processing" className="ui-sec">
        {t("status.processing")}
      </h3>
      <ul className="card grid grid-cols-2 gap-3 sm:grid-cols-4">
        {Object.entries(counts ?? {}).map(([state, count]) => (
          <li key={state} className="flex flex-col gap-1">
            <span className="font-mono text-2xl leading-none text-text-1">{count}</span>
            <span className="text-[13px] text-text-3">{String(t(`documents.stateShort.${state}`, { defaultValue: state }))}</span>
          </li>
        ))}
      </ul>
    </section>
  );
}

function Jobs({ jobs }: { jobs: SystemStatus["jobs"] }) {
  const { t, i18n } = useTranslation();
  return (
    <section aria-labelledby="jobs" className="flex flex-col gap-2">
      <h3 id="jobs" className="ui-sec">
        {t("status.jobs.title")}
      </h3>
      <p className="text-[13px] leading-relaxed text-text-3">{t("status.jobs.intro")}</p>
      <ul className="border-t border-line">
        {jobs.map((job) => (
          <li key={job.name} className="ui-row items-start" data-testid={`job-${job.name}`}>
            <span className="ui-row-text">
              <b>{jobName(t, job.name)}</b>
              <span>
                {i18n.exists(`status.jobs.purpose.${job.name}`) && <>{t(`status.jobs.purpose.${job.name}`)} </>}
                {t("status.jobs.runs", { interval: jobInterval(t, job.intervalSeconds) })}
              </span>
              <small className="font-mono text-xs text-text-4">
                {t("status.lastSuccess", { date: job.lastSuccessAt ? formatDateTime(job.lastSuccessAt, i18n.language) : t("status.never") })}
                {job.nextRunAt && <> · {t("status.jobs.nextRun", { date: formatDateTime(job.nextRunAt, i18n.language) })}</>}
              </small>
              {job.consecutiveFailures > 0 && job.lastFailureAt && (
                <small className="font-mono text-xs text-danger">
                  {t("status.jobs.lastFailure", { date: formatDateTime(job.lastFailureAt, i18n.language), count: job.consecutiveFailures })}
                </small>
              )}
            </span>
            <StateWord state={`JOB_${job.state}`} label={t(`status.jobs.state.${job.state}`)} />
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
    <section aria-labelledby="exports" className="flex flex-col gap-2">
      <h3 id="exports" className="ui-sec">
        {t("status.exports")}
      </h3>
      {message && <InfoBox>{message}</InfoBox>}
      {exports && exports.length === 0 && <EmptyState>{t("status.noExports")}</EmptyState>}
      {exports && exports.length > 0 && (
        <ul className="border-t border-line">
          {exports.map((e) => (
            <li key={e.generation} className="ui-row">
              <span className="ui-row-text">
                <span className="font-mono text-text-2">
                  {t("status.exportLine", { generation: e.generation, documents: e.documentCount, tasks: e.taskCount })}
                </span>
                {(e.trigger === "MANUAL" || e.trigger === "SCHEDULED") && <span className="text-text-2">{t(`status.exportTrigger.${e.trigger}`)}</span>}
                {e.missingOriginals.length > 0 && (
                  <span className="text-danger">{t("status.missingOriginals", { count: e.missingOriginals.length })}</span>
                )}
              </span>
            </li>
          ))}
        </ul>
      )}
      <div>
        <button type="button" className="btn-secondary" onClick={() => void createExport()} disabled={exporting} data-testid="create-export">
          {exporting ? t("status.exporting") : t("status.createExport")}
        </button>
      </div>
    </section>
  );
}
