import { useCallback, useEffect, useState } from "react";
import { Link, useSearchParams } from "react-router";
import { useTranslation } from "react-i18next";
import { api, unwrap } from "../../api/client";
import type { components } from "../../api/schema";
import { EmptyState, ErrorBox, Loading } from "../../components/common";
import { Toast, cardClass } from "../../components/ui";
import { formatDateTime } from "../../lib/format";
import { reviewTarget, reviewTone } from "../../lib/inbox";
import { reviewTitle } from "../../lib/notices";
import { problemMessage } from "../../lib/problem";

type ReviewItem = components["schemas"]["ReviewItemDto"];

/** Open cards first; the other states are the history of decided, hidden and outdated cards. */
const STATUSES = ["OPEN", "RESOLVED", "DISMISSED", "SUPERSEDED"] as const;

interface Props {
  /** Runs after every load, which reconciles and may open or close cards; tells whether the open cards are shown. */
  onLoaded: (openShown: boolean) => void;
  hidden?: boolean;
  /** On a phone the segment names the section, so its heading is only read out. */
  compactHeading?: boolean;
}

/**
 * The "To review" section of the inbox: the shared review cards (ADR-0009). Each card names its category,
 * says what happened and why it matters, and offers one highlighted action with "Hide" quietly next to it. A failed
 * step is broken and therefore red.
 */
export function ReviewSection({ onLoaded, hidden = false, compactHeading = false }: Props) {
  const { t, i18n } = useTranslation();
  const [search] = useSearchParams();
  const focusedId = search.get("item");
  const [items, setItems] = useState<ReviewItem[] | null>(null);
  const [status, setStatus] = useState<(typeof STATUSES)[number]>("OPEN");
  const [busy, setBusy] = useState<string | null>(null);
  const [dismissed, setDismissed] = useState<string | null>(null);
  // An action's error stays until the next action; the reload after a failed action only replaces the load error.
  const [error, setError] = useState<string | null>(null);
  const [loadError, setLoadError] = useState<string | null>(null);

  const load = useCallback(async () => {
    try {
      setItems(unwrap(await api.GET("/api/reviews", { params: { query: { status } } })));
      setLoadError(null);
      onLoaded(status === "OPEN");
    } catch (failure) {
      setLoadError(problemMessage(t, failure));
    }
  }, [status, t, onLoaded]);

  useEffect(() => {
    void load();
  }, [load]);

  useEffect(() => {
    if (items && focusedId) document.getElementById(`review-${focusedId}`)?.scrollIntoView({ block: "center" });
  }, [focusedId, items]);

  const change = async (item: ReviewItem, action?: string) => {
    setBusy(item.id);
    setError(null);
    try {
      const body = { expectedVersion: item.version };
      if (action) unwrap(await api.POST("/api/reviews/{id}/actions/{action}", { params: { path: { id: item.id, action } }, body }));
      else {
        unwrap(await api.POST("/api/reviews/{id}/dismiss", { params: { path: { id: item.id } }, body }));
        setDismissed(item.id);
      }
      await load();
    } catch (failure) {
      setError(problemMessage(t, failure));
      await load();
    } finally {
      setBusy(null);
    }
  };

  const open = status === "OPEN";
  const openCount = open && items ? items.length : null;

  return (
    <section hidden={hidden} aria-labelledby="to-review" className="flex flex-col gap-3" data-testid="inbox-review">
      <h2 id="to-review" className={compactHeading ? "sr-only" : "ui-sec"}>
        <span>{t("review.title")}</span>
        {openCount !== null && <span>{openCount}</span>}
      </h2>
      <ErrorBox message={error ?? loadError} />
      {!items && !loadError && <Loading />}
      {items?.length === 0 && <EmptyState>{open ? t("review.empty") : t("review.emptyHistory")}</EmptyState>}
      {items && items.length > 0 && (
        <ul className="flex flex-col gap-2" aria-live="polite">
          {items.map((item) => {
            const target = reviewTarget(item.linkType, item.linkId);
            const danger = reviewTone(item.reason) === "danger";
            const [firstAction, ...otherActions] = item.status === "OPEN" ? item.actions : [];
            const disabled = busy === item.id;
            return (
              <li
                id={`review-${item.id}`}
                key={item.id}
                className={cardClass(danger ? "danger" : undefined, focusedId === item.id, "flex flex-col gap-2")}
                data-testid="review-card"
                data-tone={danger ? "danger" : undefined}
              >
                <p className={`ui-eyebrow ${danger ? "text-danger" : ""}`}>{t(`review.category.${item.category}`)}</p>
                <p className="text-[15px] leading-snug font-semibold break-words">{reviewTitle(t, item.titleKey, item.params)}</p>
                <p className="text-[13px] leading-relaxed text-text-3">{t(`review.reason.${item.reason}`)}</p>
                {!open && (
                  <p className="ui-meta">
                    <span>{formatDateTime(item.updatedAt, i18n.language)}</span>
                    <span>{t(`review.status.${item.status}`)}</span>
                  </p>
                )}
                <div className="flex flex-wrap gap-2">
                  {firstAction && (
                    <button type="button" className="btn-secondary" disabled={disabled} onClick={() => void change(item, firstAction)}>
                      {t(`review.action.${firstAction}`)}
                    </button>
                  )}
                  {target && (
                    <Link to={target.to} className={firstAction ? "btn-ghost" : "btn-secondary"}>
                      {t(target.labelKey)}
                    </Link>
                  )}
                  {otherActions.map((action) => (
                    <button key={action} type="button" className="btn-ghost" disabled={disabled} onClick={() => void change(item, action)}>
                      {t(`review.action.${action}`)}
                    </button>
                  ))}
                  {item.status === "OPEN" && (
                    <button type="button" className="btn-ghost" disabled={disabled} onClick={() => void change(item)} data-testid="review-dismiss">
                      {t("review.dismiss")}
                    </button>
                  )}
                </div>
              </li>
            );
          })}
        </ul>
      )}
      {/* The history is rarely needed, so its states follow the cards instead of competing with them. */}
      <p className="ui-sec mt-2">{t("review.historyTitle")}</p>
      <div className="ui-rail lg:mx-0 lg:flex-wrap lg:px-0" role="group" aria-label={t("review.history")} data-testid="review-status">
        {STATUSES.map((option) => (
          <button key={option} type="button" className="ui-pill" aria-pressed={status === option} onClick={() => setStatus(option)}>
            {t(`review.status.${option}`)}
          </button>
        ))}
      </div>
      {dismissed && (
        <Toast key={dismissed} title={t("review.dismissedTitle")} onDismiss={() => setDismissed(null)}>
          {t("review.dismissedHint")}
        </Toast>
      )}
    </section>
  );
}
