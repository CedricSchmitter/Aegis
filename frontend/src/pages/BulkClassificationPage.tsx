import { useCallback, useEffect, useMemo, useState } from "react";
import { Link, useSearchParams } from "react-router";
import { useTranslation } from "react-i18next";
import { api, toApiError, unwrap } from "../api/client";
import type { BulkAnalysisDto, BulkPreviewDto, CategoryDto } from "../api/types";
import { useSession } from "../auth/SessionContext";
import { BackLink, BigTitle, EmptyState, ErrorBox, InfoBox, Loading } from "../components/common";
import { Checkbox, Dialog } from "../components/ui";
import {
  RUN_POLL_MS,
  SCOPE_ALL,
  SCOPE_UNSORTED,
  doneCount,
  progressPercent,
  scopeName,
  scopeOf,
  shownRun,
  skipReason,
  stopReasonText,
} from "../lib/bulkAnalysis";
import { failureReasonText } from "../lib/failureReasons";
import { formatDateTime, formatMoney } from "../lib/format";
import { problemMessage } from "../lib/problem";
import { buildTree, flatten } from "../lib/tree";

/**
 * Classification of existing documents in bulk (issue #115, ADR-0022). A member picks the documents (the whole
 * collection, Unsorted or a folder), sees how many were never classified and what that is expected to cost against the
 * daily AI budget, and starts the run explicitly. While it runs the page shows its progress and lets any member cancel
 * it; afterwards it groups the results: documents to review, identical folder proposals, failures and skips by reason.
 */
export function BulkClassificationPage() {
  const { t, i18n } = useTranslation();
  const { memberName } = useSession();
  const [params, setParams] = useSearchParams();
  const [runs, setRuns] = useState<BulkAnalysisDto[] | null>(null);
  const [categories, setCategories] = useState<CategoryDto[]>([]);
  const [scope, setScope] = useState(SCOPE_ALL);
  const [includeSubfolders, setIncludeSubfolders] = useState(true);
  const [preview, setPreview] = useState<BulkPreviewDto | null>(null);
  const [previewing, setPreviewing] = useState(false);
  const [busy, setBusy] = useState(false);
  const [confirming, setConfirming] = useState(false);
  const [loadError, setLoadError] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [message, setMessage] = useState<string | null>(null);

  const loadRuns = useCallback(async () => {
    try {
      setRuns(unwrap(await api.GET("/api/documents/bulk-analyses")));
      setLoadError(null);
    } catch (failure) {
      setLoadError(problemMessage(t, failure));
    }
  }, [t]);

  useEffect(() => {
    void loadRuns();
    void api.GET("/api/categories").then((response) => setCategories(unwrap(response).categories), () => undefined);
  }, [loadRuns]);

  const running = runs?.find((run) => run.state === "RUNNING") ?? null;
  const shown = runs ? shownRun(runs, params.get("run")) : null;

  useEffect(() => {
    if (!running) return;
    const timer = window.setTimeout(() => void loadRuns(), RUN_POLL_MS);
    return () => window.clearTimeout(timer);
  }, [running, runs, loadRuns]);

  const loadPreview = useCallback(async () => {
    setPreviewing(true);
    try {
      setPreview(unwrap(await api.POST("/api/documents/bulk-analyses/preview", { body: scopeOf(scope, includeSubfolders) })));
    } catch (failure) {
      setPreview(null);
      setError(problemMessage(t, failure));
    } finally {
      setPreviewing(false);
    }
  }, [scope, includeSubfolders, t]);

  // The preview only matters while nothing runs; it changes nothing on the server.
  const idle = runs !== null && !running;
  useEffect(() => {
    if (idle) void loadPreview();
  }, [idle, loadPreview]);

  const folders = useMemo(
    () => flatten(buildTree(categories)).map((node) => ({ id: node.category.id, text: `${"  ".repeat(node.depth)}${node.category.name}` })),
    [categories],
  );

  const start = async () => {
    if (!preview) return;
    setBusy(true);
    setError(null);
    setMessage(null);
    try {
      const run = unwrap(
        await api.POST("/api/documents/bulk-analyses", {
          body: { scope: scopeOf(scope, includeSubfolders), expectedDocuments: preview.documents },
        }),
      );
      setMessage(t("bulkAnalysis.started"));
      setParams({ run: run.id }, { replace: true });
    } catch (failure) {
      setError(problemMessage(t, failure));
      if (toApiError(failure).code === "analysis.bulk.changed") await loadPreview();
    } finally {
      setBusy(false);
      await loadRuns();
    }
  };

  const cancel = async (run: BulkAnalysisDto) => {
    setConfirming(false);
    setBusy(true);
    setError(null);
    try {
      unwrap(await api.POST("/api/documents/bulk-analyses/{id}/cancel", { params: { path: { id: run.id } } }));
      setMessage(t("bulkAnalysis.cancelled"));
    } catch (failure) {
      setError(problemMessage(t, failure));
    } finally {
      setBusy(false);
      await loadRuns();
    }
  };

  const money = (amount: number, currency: string) => formatMoney(amount, currency, i18n.language);
  const earlier = runs?.filter((run) => run.id !== shown?.id) ?? [];

  return (
    <div className="flex max-w-3xl flex-col gap-4">
      <BackLink to="/documents">{t("bulkAnalysis.back")}</BackLink>
      <BigTitle eyebrow={t("bulkAnalysis.eyebrow")}>{t("bulkAnalysis.title")}</BigTitle>
      <p className="-mt-2 text-sm leading-relaxed text-text-3">{t("bulkAnalysis.intro")}</p>
      {message && <InfoBox>{message}</InfoBox>}
      <ErrorBox message={error ?? loadError} />
      {!runs && !loadError && <Loading />}

      {idle && (
        <section className="card flex flex-col gap-3" aria-labelledby="bulk-preview" data-testid="bulk-start">
          <h2 id="bulk-preview" className="ui-eyebrow">
            {t("bulkAnalysis.previewTitle")}
          </h2>
          <label className="block">
            <span className="label">{t("bulkAnalysis.scope")}</span>
            <select className="field" value={scope} onChange={(event) => setScope(event.target.value)} data-testid="bulk-scope">
              <option value={SCOPE_ALL}>{t("bulkAnalysis.scopeAll")}</option>
              <option value={SCOPE_UNSORTED}>{t("bulkAnalysis.scopeUnsorted")}</option>
              {folders.map((folder) => (
                <option key={folder.id} value={folder.id}>
                  {folder.text}
                </option>
              ))}
            </select>
          </label>
          {scope !== SCOPE_ALL && scope !== SCOPE_UNSORTED && (
            <Checkbox checked={includeSubfolders} onChange={(event) => setIncludeSubfolders(event.target.checked)}>
              {t("bulkAnalysis.includeSubfolders")}
            </Checkbox>
          )}
          {previewing && !preview && <Loading />}
          {preview && <Preview preview={preview} money={money} />}
          {preview && preview.refusal && (
            <p className="alert-warn" role="alert" data-testid="bulk-refusal">
              {stopReasonText(t, preview.refusal)} {t("bulkAnalysis.refused")}
            </p>
          )}
          {preview && preview.documents > 0 && (
            <div>
              <button
                type="button"
                className="btn-primary w-full sm:w-auto"
                disabled={busy || previewing || Boolean(preview.refusal)}
                onClick={() => void start()}
                data-testid="bulk-start-button"
              >
                {t("bulkAnalysis.start", { count: preview.documents })}
              </button>
            </div>
          )}
        </section>
      )}

      {shown && (
        <RunView
          run={shown}
          busy={busy}
          onCancel={() => setConfirming(true)}
          memberName={(id) => memberName(id) ?? t("app.unknownMember")}
        />
      )}

      {earlier.length > 0 && (
        <section className="flex flex-col gap-2" aria-labelledby="bulk-earlier">
          <h2 id="bulk-earlier" className="ui-sec">
            <span>{t("bulkAnalysis.recentTitle")}</span>
          </h2>
          <ul className="flex flex-col gap-2">
            {earlier.map((run) => (
              <li key={run.id} className="card flex flex-col gap-1" data-testid="bulk-earlier-run">
                <p className="ui-meta">
                  <span>{t(`bulkAnalysis.state.${run.state}`)}</span>
                  <span>{formatDateTime(run.requestedAt, i18n.language)}</span>
                  <span>{scopeName(t, run.scope)}</span>
                </p>
                <p className="text-sm">{t("bulkAnalysis.runSummary", { classified: run.classified, total: run.total })}</p>
                <Link to={`?run=${run.id}`} className="self-start text-sm text-action hover:underline">
                  {t("bulkAnalysis.showRun")}
                </Link>
              </li>
            ))}
          </ul>
        </section>
      )}

      <Dialog
        open={confirming && running !== null}
        onClose={() => setConfirming(false)}
        eyebrow={t("ui.confirm.eyebrow")}
        title={t("bulkAnalysis.cancelTitle")}
        actions={
          <>
            <button type="button" className="btn-ghost" onClick={() => setConfirming(false)}>
              {t("bulkAnalysis.keepRunning")}
            </button>
            <button type="button" className="btn-secondary" onClick={() => running && void cancel(running)} data-testid="bulk-cancel-confirm">
              {t("bulkAnalysis.cancelRun")}
            </button>
          </>
        }
      >
        <p className="text-sm leading-relaxed text-text-2">{t("bulkAnalysis.cancelBody")}</p>
      </Dialog>
    </div>
  );
}

function Preview({ preview, money }: { preview: BulkPreviewDto; money: (amount: number, currency: string) => string }) {
  const { t } = useTranslation();
  const cost = preview.cost;
  if (preview.documents === 0) return <EmptyState>{t("bulkAnalysis.previewEmpty")}</EmptyState>;
  const rest = preview.documents - preview.sample.length;
  return (
    <div className="flex flex-col gap-3" data-testid="bulk-preview">
      <p className="font-semibold" data-testid="bulk-preview-count">
        {t("bulkAnalysis.previewCount", { count: preview.documents })}
      </p>
      <ul className="flex flex-col gap-1 text-sm">
        {preview.sample.map((document) => (
          <li key={document.id} className="break-words">
            <Link to={`/documents/${document.id}`} className="hover:text-data">
              {document.title}
            </Link>
          </li>
        ))}
        {rest > 0 && <li className="text-text-3">{t("bulkAnalysis.more", { count: rest })}</li>}
      </ul>
      {preview.limited && <p className="text-sm text-text-3">{t("bulkAnalysis.limited", { max: preview.maxDocuments })}</p>}
      <div className="flex flex-col gap-1 text-sm" data-testid="bulk-cost">
        <p className="ui-eyebrow">{t("bulkAnalysis.costTitle")}</p>
        <p>
          {t("bulkAnalysis.costEstimate", { total: money(cost.total, cost.currency), maximum: money(cost.maximum, cost.currency) })}
        </p>
        <p className="text-text-3">
          {t(`bulkAnalysis.basis.${cost.basis === "AVERAGE" ? "AVERAGE" : "RUN_BUDGET"}`, {
            count: cost.samples,
            amount: money(cost.perDocument, cost.currency),
          })}
        </p>
        <p>
          {t("bulkAnalysis.budgetToday", {
            limit: money(cost.dailyLimit, cost.currency),
            spent: money(cost.spentToday, cost.currency),
            count: cost.documentsToday,
          })}
        </p>
        <p>{cost.days === null || cost.days === undefined ? t("bulkAnalysis.daysUnknown") : t("bulkAnalysis.days", { count: cost.days })}</p>
        <p className="text-xs text-text-3">{t("bulkAnalysis.estimateHint")}</p>
      </div>
    </div>
  );
}

interface RunViewProps {
  run: BulkAnalysisDto;
  busy: boolean;
  onCancel: () => void;
  memberName: (id: string) => string;
}

function RunView({ run, busy, onCancel, memberName }: RunViewProps) {
  const { t, i18n } = useTranslation();
  const isRunning = run.state === "RUNNING";
  const counts = (
    [
      ["classified", run.classified],
      ["needsReview", run.needsReview],
      ["failed", run.failed],
      ["skipped", run.skipped],
      ["pending", run.pending],
      ["notClassified", run.notClassified],
      ["cancelled", run.cancelled],
      ["superseded", run.superseded],
    ] as const
  ).filter(([, value]) => value > 0);

  return (
    <section className="card flex flex-col gap-3" aria-labelledby="bulk-run" data-testid="bulk-run" data-state={run.state}>
      <div className="flex flex-wrap items-baseline justify-between gap-2">
        <h2 id="bulk-run" className="text-lg font-semibold">
          {t(`bulkAnalysis.state.${run.state}`)}
        </h2>
        <span className="font-mono text-xs text-text-3">{scopeName(t, run.scope)}</span>
      </div>
      <p className="ui-meta">
        <span>{t("bulkAnalysis.startedBy", { name: memberName(run.requestedBy), date: formatDateTime(run.requestedAt, i18n.language) })}</span>
        {run.cancelledBy && run.cancelledAt && (
          <span>{t("bulkAnalysis.cancelledBy", { name: memberName(run.cancelledBy), date: formatDateTime(run.cancelledAt, i18n.language) })}</span>
        )}
        {!run.cancelledBy && run.finishedAt && <span>{t("bulkAnalysis.finishedAt", { date: formatDateTime(run.finishedAt, i18n.language) })}</span>}
      </p>
      <div>
        <p className="text-sm" data-testid="bulk-progress">
          {t("bulkAnalysis.progress", { done: doneCount(run), total: run.total })}
        </p>
        <span
          className="ui-meter max-w-none"
          role="progressbar"
          aria-valuemin={0}
          aria-valuemax={100}
          aria-valuenow={progressPercent(run)}
          aria-label={t("bulkAnalysis.progress", { done: doneCount(run), total: run.total })}
        >
          <span style={{ width: `${progressPercent(run)}%` }} />
        </span>
      </div>
      {isRunning && run.inFlight > 0 && <p className="text-sm text-text-3">{t("bulkAnalysis.inFlight", { count: run.inFlight })}</p>}
      {run.waitingForBudgetUntil && (
        <p className="alert-info" role="status" data-testid="bulk-waiting">
          {t("bulkAnalysis.waitingForBudget", { date: formatDateTime(run.waitingForBudgetUntil, i18n.language) })}
        </p>
      )}
      {run.stopCode && (
        <p className="alert-warn" role="status">
          {t("bulkAnalysis.stopped", { reason: stopReasonText(t, run.stopCode) })}
        </p>
      )}
      {counts.length > 0 && (
        <dl className="grid grid-cols-2 gap-x-4 gap-y-1 text-sm sm:grid-cols-4" data-testid="bulk-counts">
          {counts.map(([key, value]) => (
            <div key={key} className="flex flex-col">
              <dt className="text-text-3">{t(`bulkAnalysis.count.${key}`)}</dt>
              <dd className="font-mono text-base" data-count={key}>
                {value}
              </dd>
            </div>
          ))}
        </dl>
      )}
      {isRunning && (
        <div>
          <button type="button" className="btn-secondary" disabled={busy} onClick={onCancel} data-testid="bulk-cancel">
            {t("bulkAnalysis.cancelRun")}
          </button>
        </div>
      )}
      <Results run={run} />
    </section>
  );
}

function Results({ run }: { run: BulkAnalysisDto }) {
  const { t } = useTranslation();
  return (
    <>
      {run.reviewDocuments.length > 0 && (
        <div className="flex flex-col gap-1" data-testid="bulk-review">
          <h3 className="ui-eyebrow">{t("bulkAnalysis.reviewTitle")}</h3>
          <ul className="flex flex-col gap-1 text-sm">
            {run.reviewDocuments.map((document) => (
              <li key={document.id} className="break-words">
                <Link to={`/documents/${document.id}`} className="text-action hover:underline">
                  {document.title}
                </Link>
              </li>
            ))}
            {run.needsReview > run.reviewDocuments.length && (
              <li className="text-text-3">{t("bulkAnalysis.more", { count: run.needsReview - run.reviewDocuments.length })}</li>
            )}
          </ul>
        </div>
      )}
      {run.placementGroups.length > 0 && (
        <div className="flex flex-col gap-2" data-testid="bulk-placements">
          <h3 className="ui-eyebrow">{t("bulkAnalysis.placementTitle")}</h3>
          <ul className="flex flex-col gap-2 text-sm">
            {run.placementGroups.map((group) => (
              <li key={group.key} className="flex flex-col gap-0.5">
                <span className="break-words">{t("bulkAnalysis.placementGroup", { path: group.path.join(" / "), count: group.documents })}</span>
                {group.newFolders.length > 0 && (
                  <span className="text-text-3">{t("bulkAnalysis.placementNew", { names: group.newFolders.join(" / ") })}</span>
                )}
                <Link to={`/documents/${group.example.id}`} className="self-start text-action hover:underline">
                  {t("bulkAnalysis.placementOpen")}
                </Link>
              </li>
            ))}
          </ul>
          <p className="text-xs text-text-3">{t("bulkAnalysis.placementHint")}</p>
        </div>
      )}
      {run.failures.length > 0 && (
        <div className="flex flex-col gap-1" data-testid="bulk-failures">
          <h3 className="ui-eyebrow text-danger">{t("bulkAnalysis.failuresTitle")}</h3>
          <ReasonList groups={run.failures} reason={(code) => failureReasonText(t, code)} />
          <p className="text-xs text-text-3">{t("bulkAnalysis.failuresHint")}</p>
        </div>
      )}
      {run.skips.length > 0 && (
        <div className="flex flex-col gap-1" data-testid="bulk-skips">
          <h3 className="ui-eyebrow">{t("bulkAnalysis.skipsTitle")}</h3>
          <ReasonList groups={run.skips} reason={(code) => String(t(`bulkAnalysis.skipReason.${skipReason(code)}`))} />
        </div>
      )}
    </>
  );
}

function ReasonList({ groups, reason }: { groups: BulkAnalysisDto["failures"]; reason: (code: string) => string }) {
  const { t } = useTranslation();
  return (
    <ul className="flex flex-col gap-1 text-sm">
      {groups.map((group) => (
        <li key={group.code} className="flex flex-col gap-0.5">
          <span>{t("bulkAnalysis.group", { count: group.documents, reason: reason(group.code) })}</span>
          <span className="flex flex-wrap gap-x-3 text-text-3">
            {group.examples.map((document) => (
              <Link key={document.id} to={`/documents/${document.id}`} className="break-words hover:text-data">
                {document.title}
              </Link>
            ))}
          </span>
        </li>
      ))}
    </ul>
  );
}
