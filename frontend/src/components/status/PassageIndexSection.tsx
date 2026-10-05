import { useCallback, useEffect, useState } from "react";
import { useTranslation } from "react-i18next";
import { api, unwrap } from "../../api/client";
import type { PassageCoverage } from "../../api/types";
import { formatDateTime } from "../../lib/format";
import { problemMessage } from "../../lib/problem";
import { ErrorBox, InfoBox, Loading } from "../common";

/** Technical coverage is shown separately from whether a particular clause matches a lexical query. */
export function PassageIndexSection() {
  const { t, i18n } = useTranslation();
  const [coverage, setCoverage] = useState<PassageCoverage | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [requested, setRequested] = useState(false);
  const [busy, setBusy] = useState(false);
  const load = useCallback(async () => {
    try {
      setCoverage(unwrap(await api.GET("/api/documents/passages/coverage")));
      setError(null);
    } catch (failure) { setError(problemMessage(t, failure)); }
  }, [t]);
  useEffect(() => { void load(); }, [load]);
  const rebuild = async () => {
    setBusy(true);
    try {
      unwrap(await api.POST("/api/documents/passages/rebuild"));
      setRequested(true);
      await load();
    } catch (failure) { setError(problemMessage(t, failure)); }
    finally { setBusy(false); }
  };
  const number = new Intl.NumberFormat(i18n.language);
  return (
    <section aria-labelledby="passage-index-title" className="card flex flex-col gap-3" data-testid="passage-index">
      <h3 id="passage-index-title" className="ui-sec">{t("passages.title")}</h3>
      <p className="text-sm text-text-3">{t("passages.intro")}</p>
      <ErrorBox message={error} />
      {!coverage && !error && <Loading />}
      {requested && <InfoBox>{t("passages.requested")}</InfoBox>}
      {coverage && <>
        <p data-testid="passage-progress">{coverage.total === 0 ? t("passages.empty") : t("passages.progress", { current: number.format(coverage.current), total: number.format(coverage.total) })}</p>
        {coverage.total > 0 && <progress className="w-full" value={coverage.current} max={coverage.total} aria-label={t("passages.progress", { current: number.format(coverage.current), total: number.format(coverage.total) })} />}
        <dl className="grid grid-cols-2 gap-x-4 gap-y-1 text-sm text-text-3">
          {(["pending", "stale", "failed", "noText", "sourceUnavailable"] as const).map((state) => (
            <div key={state} className="flex justify-between gap-2"><dt>{t(`passages.state.${state}`)}</dt><dd>{number.format(coverage[state])}</dd></div>
          ))}
        </dl>
        <p className="text-sm text-text-3">{t(coverage.complete ? "passages.complete" : "passages.incomplete")}</p>
        <p className="text-xs text-text-4">{t("passages.freshness", { hours: number.format(coverage.freshnessSeconds / 3600), date: coverage.oldestVerifiedAt ? formatDateTime(coverage.oldestVerifiedAt, i18n.language) : t("status.never") })}</p>
      </>}
      <div className="flex flex-wrap gap-2">
        <button type="button" className="btn-secondary" onClick={() => void load()}>{t("passages.refresh")}</button>
        <button type="button" className="btn-secondary" onClick={() => void rebuild()} disabled={busy}>{t(busy ? "passages.requesting" : "passages.rebuild")}</button>
      </div>
    </section>
  );
}
