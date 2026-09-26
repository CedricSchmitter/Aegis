import { useCallback, useEffect, useState } from "react";
import { Link, useLocation } from "react-router";
import { useTranslation } from "react-i18next";
import { api, unwrap } from "../../api/client";
import type { NoticeSummary } from "../../api/types";
import { EmptyState, ErrorBox, Loading } from "../../components/common";
import { formatDateTime } from "../../lib/format";
import { INBOX_CHANGED, MESSAGES_ANCHOR, noticeTarget } from "../../lib/inbox";
import { noticeText } from "../../lib/notices";
import { problemMessage } from "../../lib/problem";

/**
 * The "Messages" section of the inbox: the member's notices that no review card represents, such as task
 * assignments, cancellation reminders and operational notices (issue #83).
 */
export function MessagesSection() {
  const { t, i18n } = useTranslation();
  const { hash } = useLocation();
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

  const loaded = notices !== null;
  useEffect(() => {
    if (loaded && hash === `#${MESSAGES_ANCHOR}`) document.getElementById(MESSAGES_ANCHOR)?.scrollIntoView();
  }, [loaded, hash]);

  const changed = () => window.dispatchEvent(new Event(INBOX_CHANGED));

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
    <section aria-labelledby={MESSAGES_ANCHOR} data-testid="inbox-messages">
      <div className="mb-2 flex flex-wrap items-center justify-between gap-2">
        <h2 id={MESSAGES_ANCHOR} className="text-lg font-semibold">
          {t("notices.title")}
        </h2>
        <div className="flex items-center gap-3">
          <label className="flex items-center gap-2 text-sm">
            <input type="checkbox" checked={unreadOnly} onChange={(e) => setUnreadOnly(e.target.checked)} />
            {t("notices.unreadOnly")}
          </label>
          <button type="button" className="btn-secondary" onClick={markAll} data-testid="mark-all-read">
            {t("notices.markAllRead")}
          </button>
        </div>
      </div>
      <ErrorBox message={error} />
      {!notices && !error && <Loading />}
      {notices && notices.length === 0 && <EmptyState>{unreadOnly ? t("notices.emptyUnread") : t("notices.empty")}</EmptyState>}
      {notices && (
        <ul className="flex flex-col gap-2">
          {notices.map((notice) => {
            const target = noticeTarget(notice);
            const unread = !notice.readAt;
            return (
              <li key={notice.id} className={`card flex flex-col gap-1 ${unread ? "border-sky-400 dark:border-sky-600" : ""}`} data-testid="notice" data-unread={unread}>
                <p className={unread ? "font-semibold" : ""}>{noticeText(t, notice, i18n.language)}</p>
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
    </section>
  );
}
