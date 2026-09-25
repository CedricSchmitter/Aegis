import { useCallback, useEffect, useState } from "react";
import { useTranslation } from "react-i18next";
import { api, unwrap } from "../api/client";
import type { ExportSummary, SystemStatus, VersionResponse } from "../api/types";
import { AiSection } from "../components/AiSection";
import { Badge, EmptyState, ErrorBox, InfoBox, Loading, PageTitle } from "../components/common";
import { StorageSection } from "../components/StorageSection";
import { formatDateTime } from "../lib/format";
import { jobInterval, needsAttention } from "../lib/jobs";
import { problemMessage } from "../lib/problem";
import { backupNeedsAttention } from "../lib/storage";

export function StatusPage() {
  const { t, i18n } = useTranslation();
  const [status, setStatus] = useState<SystemStatus | null>(null);
  const [exports, setExports] = useState<ExportSummary[] | null>(null);
  const [version, setVersion] = useState<VersionResponse | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [message, setMessage] = useState<string | null>(null);
  const [exporting, setExporting] = useState(false);
  const backupHealthy = status ? !backupNeedsAttention(status.storage.backup) : false;
  const diskLow = status?.storage.disks.some((disk) => disk.state === "LOW") ?? false;

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

  const createExport = async () => {
    setExporting(true);
    setMessage(null);
    try {
      const created = unwrap(await api.POST("/api/exports"));
      setMessage(t("status.exportCreated", { generation: created.generation }));
      await load();
    } catch (failure) {
      setError(problemMessage(t, failure));
    } finally {
      setExporting(false);
    }
  };

  return (
    <>
      <PageTitle>{t("status.title")}</PageTitle>
      <ErrorBox message={error} />
      {!status && !error && <Loading />}
      {status && (
        <div className="flex flex-col gap-4">
          {!backupHealthy && <p className="alert-warn" role="note">{t("status.backupWarning")}</p>}
          {diskLow && <p className="alert-warn" role="note" data-testid="storage-warning">{t("status.storageWarning")}</p>}
          {status.jobs.some(needsAttention) && <p className="alert-warn" role="note" data-testid="jobs-warning">{t("status.jobs.warning")}</p>}
          <section aria-labelledby="integrations">
            <h2 id="integrations" className="mb-2 text-lg font-semibold">
              {t("status.integrations")}
            </h2>
            <ul className="flex flex-col gap-2">
              {/* The external backup has its own section with the reason and the disks. */}
              {status.integrations.filter((integration) => integration.name !== "externalBackup").map((integration) => (
                <li key={integration.name} className="card flex flex-wrap items-center justify-between gap-2" data-testid={`integration-${integration.name}`}>
                  <div>
                    <p className="font-medium">{t(`status.integration.${integration.name}`, { defaultValue: integration.name })}</p>
                    {integration.detail && <p className="text-sm text-stone-600 dark:text-stone-400">{integration.detail}</p>}
                    <p className="text-xs text-stone-500">
                      {t("status.lastSuccess", { date: integration.lastSuccessAt ? formatDateTime(integration.lastSuccessAt, i18n.language) : t("status.never") })}
                      {integration.checkedAt && <> · {t("status.checkedAt", { date: formatDateTime(integration.checkedAt, i18n.language) })}</>}
                    </p>
                  </div>
                  <Badge state={integration.state} label={t(`status.state.${integration.state}`)} />
                </li>
              ))}
            </ul>
          </section>
          <StorageSection storage={status.storage} />
          <section aria-labelledby="processing">
            <h2 id="processing" className="mb-2 text-lg font-semibold">
              {t("status.processing")}
            </h2>
            <ul className="card grid grid-cols-2 gap-2 sm:grid-cols-4">
              {Object.entries(status.documentProcessing ?? {}).map(([state, count]) => (
                <li key={state} className="flex flex-col">
                  <span className="text-2xl font-semibold">{count}</span>
                  <span className="text-sm text-stone-600 dark:text-stone-400">{String(t(`documents.stateShort.${state}`, { defaultValue: state }))}</span>
                </li>
              ))}
            </ul>
          </section>
          <section aria-labelledby="jobs">
            <h2 id="jobs" className="mb-1 text-lg font-semibold">
              {t("status.jobs.title")}
            </h2>
            <p className="mb-2 text-sm text-stone-600 dark:text-stone-400">{t("status.jobs.intro")}</p>
            <ul className="flex flex-col gap-2">
              {status.jobs.map((job) => (
                <li key={job.name} className="card flex flex-wrap items-center justify-between gap-2" data-testid={`job-${job.name}`}>
                  <div className="min-w-0">
                    <p className="font-medium">{t(`status.jobs.name.${job.name}`, { defaultValue: job.name })}</p>
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
          <AiSection />
          <section aria-labelledby="exports">
            <div className="mb-2 flex items-center justify-between">
              <h2 id="exports" className="text-lg font-semibold">
                {t("status.exports")}
              </h2>
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
          {version && <p className="text-xs text-stone-500">{t("status.version", { commit: version.commit, time: version.buildTime })}</p>}
        </div>
      )}
    </>
  );
}
