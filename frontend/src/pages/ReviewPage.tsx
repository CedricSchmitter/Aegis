import { useCallback, useEffect, useMemo, useState } from "react";
import { Link, useSearchParams } from "react-router";
import { useTranslation } from "react-i18next";
import { api, unwrap } from "../api/client";
import type { components } from "../api/schema";
import { EmptyState, ErrorBox, Loading, PageTitle } from "../components/common";
import { formatDateTime } from "../lib/format";
import { problemMessage } from "../lib/problem";

type ReviewItem = components["schemas"]["ReviewItemDto"];

function target(item: ReviewItem) {
  if (item.linkType === "DOCUMENT") return `/documents/${item.linkId}`;
  if (item.linkType === "CONTRACT") return `/contracts/${item.linkId}`;
  return null;
}

export function ReviewPage() {
  const { t, i18n } = useTranslation();
  const [search] = useSearchParams();
  const focusedId = search.get("item");
  const [items, setItems] = useState<ReviewItem[] | null>(null);
  const [status, setStatus] = useState("OPEN");
  const [category, setCategory] = useState("");
  const [busy, setBusy] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const load = useCallback(async () => {
    try {
      setItems(unwrap(await api.GET("/api/reviews", { params: { query: { status, category: category || undefined } } })));
      setError(null);
    } catch (failure) { setError(problemMessage(t, failure)); }
  }, [category, status, t]);
  useEffect(() => { void load(); }, [load]);
  useEffect(() => {
    if (items && focusedId) document.getElementById(`review-${focusedId}`)?.scrollIntoView({ block: "center" });
  }, [focusedId, items]);
  const counts = useMemo(() => (items ?? []).reduce<Record<string, number>>((all, item) => {
    all[item.category] = (all[item.category] ?? 0) + 1; return all;
  }, {}), [items]);
  const change = async (item: ReviewItem, action?: string) => {
    setBusy(item.id);
    try {
      if (action) await api.POST("/api/reviews/{id}/actions/{action}", { params: { path: { id: item.id, action } }, body: { expectedVersion: item.version } });
      else await api.POST("/api/reviews/{id}/dismiss", { params: { path: { id: item.id } }, body: { expectedVersion: item.version } });
      await load(); window.dispatchEvent(new Event("aegis:notices-changed"));
    } catch (failure) { setError(problemMessage(t, failure)); await load(); }
    finally { setBusy(null); }
  };
  return <>
    <PageTitle>{t("review.title")}</PageTitle>
    <p className="mb-4 text-sm text-stone-600 dark:text-stone-300">{t("review.intro")}</p>
    <div className="mb-4 flex flex-wrap gap-3" aria-label={t("review.filters")}>
      <label className="flex items-center gap-2 text-sm">{t("review.statusLabel")}
        <select value={status} onChange={(e) => setStatus(e.target.value)}><option value="OPEN">{t("review.status.OPEN")}</option><option value="RESOLVED">{t("review.status.RESOLVED")}</option><option value="DISMISSED">{t("review.status.DISMISSED")}</option><option value="SUPERSEDED">{t("review.status.SUPERSEDED")}</option></select>
      </label>
      <label className="flex items-center gap-2 text-sm">{t("review.categoryLabel")}
        <select value={category} onChange={(e) => setCategory(e.target.value)}><option value="">{t("review.allCategories")}</option><option value="PROCESSING_FAILURE">{t("review.category.PROCESSING_FAILURE")}</option><option value="CLASSIFICATION">{t("review.category.CLASSIFICATION")}</option><option value="CONTRACT_FACT">{t("review.category.CONTRACT_FACT")}</option></select>
      </label>
    </div>
    <ErrorBox message={error} />
    {!items && !error && <Loading />}
    {items?.length === 0 && <EmptyState>{status === "OPEN" ? t("review.empty") : t("review.emptyHistory")}</EmptyState>}
    {items && <ul className="flex flex-col gap-3" aria-live="polite">{items.map((item) => {
      const href = target(item); return <li id={`review-${item.id}`} key={item.id} className={`card border-l-4 ${focusedId === item.id ? "ring-2 ring-sky-500" : ""} ${item.priority === "HIGH" ? "border-l-red-500" : "border-l-amber-400"}`}>
        <div className="flex flex-wrap items-start justify-between gap-2"><div><p className="font-semibold">{String(t(item.titleKey, item.params))}</p><p className="mt-1 text-sm text-stone-600 dark:text-stone-300">{t(`review.reason.${item.reason}`)}</p></div><span className="badge">{t(`review.category.${item.category}`)}{counts[item.category] ? ` · ${counts[item.category]}` : ""}</span></div>
        <p className="mt-2 text-xs text-stone-500">{formatDateTime(item.updatedAt, i18n.language)} · {t(`review.status.${item.status}`)}</p>
        <div className="mt-3 flex flex-wrap gap-2">{href && <Link className="btn-secondary" to={href}>{t("review.openSource")}</Link>}{item.actions.map((action) => <button key={action} type="button" className="btn-primary" disabled={busy === item.id} onClick={() => void change(item, action)}>{t(`review.action.${action}`)}</button>)}{item.status === "OPEN" && <button type="button" className="btn-ghost" disabled={busy === item.id} onClick={() => void change(item)}>{t("review.dismiss")}</button>}</div>
      </li>;
    })}</ul>}
  </>;
}
