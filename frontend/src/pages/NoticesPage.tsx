import { useCallback, useEffect, useState } from "react";
import { Link } from "react-router";
import { useTranslation } from "react-i18next";
import { api, unwrap } from "../api/client";
import type { NoticeSummary } from "../api/types";
import { EmptyState, ErrorBox, InfoBox, Loading, PageTitle } from "../components/common";
import { formatDateTime } from "../lib/format";
import { problemMessage } from "../lib/problem";

function linkTarget(notice: NoticeSummary): string | null {
  if (notice.linkType === "TASK" && notice.linkId) return `/tasks/${notice.linkId}`;
  if (notice.linkType === "DOCUMENT" && notice.linkId) return `/documents/${notice.linkId}`;
  return null;
}

export function NoticesPage() {
  const { t, i18n } = useTranslation();
  const [notices, setNotices] = useState<NoticeSummary[] | null>(null);
  const [unreadOnly, setUnreadOnly] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const load = useCallback(async () => {
    try {
      setNotices(unwrap(await api.GET("/api/notices", { params: { query: { unreadOnly } } })));
      setError(null);
    } catch (failure) {
      setError(problemMessage(t, failure));
    }
  }, [unreadOnly, t]);

  useEffect(() => {
    void load();
  }, [load]);

  const changed = () => window.dispatchEvent(new Event("aegis:notices-changed"));

  const markRead = async (notice: NoticeSummary) => {
    try {
      const updated = unwrap(await api.POST("/api/notices/{id}/read", { params: { path: { id: notice.id } } }));
      setNotices((current) => (current ?? []).map((n) => (n.id === updated.id ? updated : n)));
      changed();
    } catch (failure) {
      setError(problemMessage(t, failure));
    }
  };

  const markAll = async () => {
    try {
      unwrap(await api.POST("/api/notices/read-all"));
      await load();
      changed();
    } catch (failure) {
      setError(problemMessage(t, failure));
    }
  };

  return (
    <>
      <PageTitle
        actions={
          <div className="flex items-center gap-3">
            <label className="flex items-center gap-2 text-sm">
              <input type="checkbox" checked={unreadOnly} onChange={(e) => setUnreadOnly(e.target.checked)} />
              {t("notices.unreadOnly")}
            </label>
            <button type="button" className="btn-secondary" onClick={markAll} data-testid="mark-all-read">
              {t("notices.markAllRead")}
            </button>
          </div>
        }
      >
        {t("notices.title")}
      </PageTitle>
      <div className="mb-3">
        <InfoBox>{t("notices.inAppOnly")}</InfoBox>
      </div>
      <ErrorBox message={error} />
      {!notices && !error && <Loading />}
      {notices && notices.length === 0 && <EmptyState>{unreadOnly ? t("notices.emptyUnread") : t("notices.empty")}</EmptyState>}
      {notices && (
        <ul className="flex flex-col gap-2">
          {notices.map((notice) => {
            const target = linkTarget(notice);
            const unread = !notice.readAt;
            return (
              <li key={notice.id} className={`card flex flex-col gap-1 ${unread ? "border-sky-400 dark:border-sky-600" : ""}`} data-testid="notice" data-unread={unread}>
                <p className={unread ? "font-semibold" : ""}>{t(`notices.kind.${notice.kind}`, { ...(notice.params ?? {}), defaultValue: notice.kind })}</p>
                <p className="text-xs text-stone-500">
                  {formatDateTime(notice.createdAt, i18n.language)}
                  {notice.readAt && <> · {t("notices.readAt", { date: formatDateTime(notice.readAt, i18n.language) })}</>}
                </p>
                <div className="flex gap-2">
                  {target && (
                    <Link to={target} className="btn-ghost" onClick={() => unread && void markRead(notice)}>
                      {t("notices.open")}
                    </Link>
                  )}
                  {unread && (
                    <button type="button" className="btn-ghost" onClick={() => markRead(notice)}>
                      {t("notices.markRead")}
                    </button>
                  )}
                </div>
              </li>
            );
          })}
        </ul>
      )}
    </>
  );
}
