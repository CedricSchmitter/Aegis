import { useCallback, useEffect, useState } from "react";
import { Link, useLocation } from "react-router";
import { useTranslation } from "react-i18next";
import { api, unwrap } from "../../api/client";
import type { NoticeSummary } from "../../api/types";
import { EmptyState, ErrorBox, Loading } from "../../components/common";
import { cardClass } from "../../components/ui";
import { formatDateTime } from "../../lib/format";
import { INBOX_CHANGED, MESSAGES_ANCHOR, noticeTarget, noticeTone } from "../../lib/inbox";
import { noticeText } from "../../lib/notices";
import { problemMessage } from "../../lib/problem";

interface Props {
  hidden?: boolean;
  /** On a phone the segment names the section, so its heading is only read out. */
  compactHeading?: boolean;
}

/**
 * The "Messages" section of the inbox: the member's notices that no review card represents, such as task
 * assignments, cancellation reminders and operational notices (issues #83 and #27). A message opens its target and
 * is read by that; anything broken, such as a failed backup, is red.
 */
export function MessagesSection({ hidden = false, compactHeading = false }: Props) {
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
    if (loaded && !hidden && hash === `#${MESSAGES_ANCHOR}`) document.getElementById(MESSAGES_ANCHOR)?.scrollIntoView();
  }, [loaded, hidden, hash]);

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

  const unread = notices?.filter((notice) => !notice.readAt).length ?? 0;

  return (
    <section hidden={hidden} aria-labelledby={MESSAGES_ANCHOR} className="flex flex-col gap-3" data-testid="inbox-messages">
      <h2 id={MESSAGES_ANCHOR} className={compactHeading ? "sr-only" : "ui-sec"}>
        <span>{t("notices.title")}</span>
        {notices && <span>{t("notices.newCount", { count: unread })}</span>}
      </h2>
      <div className="flex flex-wrap items-center gap-2">
        <button type="button" className="ui-pill" aria-pressed={unreadOnly} onClick={() => setUnreadOnly((value) => !value)}>
          {t("notices.unreadOnly")}
        </button>
        {unread > 0 && (
          <button type="button" className="btn-ghost" onClick={() => void markAll()} data-testid="mark-all-read">
            {t("notices.markAllRead")}
          </button>
        )}
      </div>
      <ErrorBox message={error} />
      {!notices && !error && <Loading />}
      {notices && notices.length === 0 && <EmptyState>{unreadOnly ? t("notices.emptyUnread") : t("notices.empty")}</EmptyState>}
      {notices && notices.length > 0 && (
        <ul className="flex flex-col gap-2">
          {notices.map((notice) => {
            const target = noticeTarget(notice);
            const isUnread = !notice.readAt;
            const tone = noticeTone(notice);
            const text = noticeText(t, notice, i18n.language);
            const textClass = `text-[15px] leading-snug break-words ${isUnread ? "font-semibold text-text-1" : "text-text-2"}`;
            return (
              <li
                key={notice.id}
                className={cardClass(tone ?? undefined, false, "flex flex-col gap-1.5")}
                data-testid="notice"
                data-unread={isUnread}
                data-tone={tone ?? undefined}
              >
                {target ? (
                  <Link to={target} className={`ui-stretch ${textClass}`} onClick={() => isUnread && void markRead(notice)}>
                    {text}
                  </Link>
                ) : (
                  <p className={textClass}>{text}</p>
                )}
                <p className="ui-meta">
                  <span>{formatDateTime(notice.createdAt, i18n.language)}</span>
                  {isUnread && <span className={tone === "danger" ? "text-danger" : "text-data"}>{t("notices.new")}</span>}
                </p>
                {isUnread && !target && (
                  <button type="button" className="btn-ghost -ml-4 self-start" onClick={() => void markRead(notice)}>
                    {t("notices.markRead")}
                  </button>
                )}
              </li>
            );
          })}
        </ul>
      )}
    </section>
  );
}
