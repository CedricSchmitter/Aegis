import { useCallback, useEffect, useMemo, useState } from "react";
import { Link } from "react-router";
import { useTranslation } from "react-i18next";
import { api, unwrap } from "../api/client";
import type { ContractDeadlineItemDto, TaskDto, TodayResponse } from "../api/types";
import { BigTitle, EmptyState, ErrorBox, Loading } from "../components/common";
import { DeadlineCard } from "../components/contracts/ContractCard";
import { ContextPanelOrFlow } from "../components/shell/ContextPanel";
import { useDesktop, useShellData } from "../components/shell/ShellData";
import { StatusLine } from "../components/shell/StatusIndicator";
import { useCompletionToast } from "../components/tasks/CompletionToast";
import { TaskCard } from "../components/tasks/TaskCard";
import { formatDate, formatWeekday } from "../lib/format";
import { problemMessage } from "../lib/problem";
import { todayBuckets, upcomingDeadlines, type TodayBucket } from "../lib/tasks";

/**
 * Today: the status line, then overdue, due today, planned today, the cancellation deadlines of the next
 * 30 days with their countdown and the next seven days. On a desktop the urgent tasks stand left and the next seven
 * days right; the deadlines and the inbox fill the context panel from 1280 px and follow below the columns before.
 */
export function TodayPage() {
  const { t, i18n } = useTranslation();
  const desktop = useDesktop();
  const [today, setToday] = useState<TodayResponse | null>(null);
  const [deadlines, setDeadlines] = useState<ContractDeadlineItemDto[]>([]);
  const [error, setError] = useState<string | null>(null);

  const load = useCallback(async () => {
    try {
      const [buckets, due] = await Promise.all([api.GET("/api/today"), api.GET("/api/contracts/deadlines")]);
      setToday(unwrap(buckets));
      setDeadlines(unwrap(due));
      setError(null);
    } catch (failure) {
      setError(problemMessage(t, failure));
    }
  }, [t]);

  useEffect(() => {
    void load();
  }, [load]);

  // A completed task leaves Today; the list is reloaded so every bucket stays the server's.
  const changed = useCallback(() => void load(), [load]);
  const { notify, toast } = useCompletionToast(changed, setError);

  const shown = useMemo(() => (today ? upcomingDeadlines(deadlines, today.date) : []), [deadlines, today]);
  const buckets = useMemo(() => (today ? todayBuckets(today, shown) : null), [today, shown]);

  const section = (key: TodayBucket, label: string) =>
    buckets && buckets[key].length > 0 ? (
      <Bucket key={key} id={key} label={label} tasks={buckets[key]} onChange={changed} onCompleted={notify} />
    ) : null;
  const overdue = section("overdue", t("today.overdue"));
  const dueToday = section("dueToday", t("today.dueToday"));
  const planned = section("plannedToday", t("today.plannedToday"));
  const upcoming = section("upcoming", t("today.upcoming"));
  const unassigned = section("unassigned", t("today.unassigned"));
  const nothing = buckets && !overdue && !dueToday && !planned && !upcoming && !unassigned && shown.length === 0;

  return (
    <>
      <BigTitle
        eyebrow={
          today && (
            <>
              {formatWeekday(today.date, i18n.language)} · <b>{formatDate(today.date, i18n.language)}</b>
            </>
          )
        }
      >
        {t("today.title")}
      </BigTitle>
      <div className="flex flex-col gap-5">
        <StatusLine />
        <ErrorBox message={error} />
        {!today && !error && <Loading />}
        {today && !desktop && (nothing ? (
          <EmptyState>{t("today.empty")}</EmptyState>
        ) : (
          <>
            {overdue}
            {dueToday}
            {planned}
            {shown.length > 0 && <Deadlines items={shown} on={today.date} />}
            {upcoming}
            {unassigned}
          </>
        ))}
        {today && desktop && (
          <>
            <div className="grid grid-cols-2 items-start gap-5.5">
              <div className="flex flex-col gap-5">
                {overdue}
                {dueToday}
                {planned}
                {!overdue && !dueToday && !planned && <p className="text-sm text-text-3">{t("today.urgentEmpty")}</p>}
              </div>
              <div className="flex flex-col gap-5">
                {upcoming ?? <p className="text-sm text-text-3">{t("today.upcomingEmpty")}</p>}
                {unassigned}
              </div>
            </div>
            <ContextPanelOrFlow>
              <div className="flex flex-col gap-5" data-testid="today-side">
                <Deadlines items={shown} on={today.date} />
                <InboxSummary />
              </div>
            </ContextPanelOrFlow>
          </>
        )}
      </div>
      {toast}
    </>
  );
}

function Bucket({
  id,
  label,
  tasks,
  onChange,
  onCompleted,
}: {
  id: TodayBucket;
  label: string;
  tasks: TaskDto[];
  onChange: (task: TaskDto) => void;
  onCompleted: (task: TaskDto) => void;
}) {
  return (
    <section className="flex flex-col gap-2" aria-labelledby={`bucket-${id}`} data-testid={`bucket-${id}`}>
      <h2 id={`bucket-${id}`} className="ui-sec" data-tone={id === "overdue" ? "danger" : undefined}>
        <span>{label}</span>
        <span>{tasks.length}</span>
      </h2>
      <ul className="flex flex-col gap-2">
        {tasks.map((task) => (
          <TaskCard key={task.id} task={task} onChange={onChange} onCompleted={onCompleted} />
        ))}
      </ul>
    </section>
  );
}

/** Cancellation deadlines of the next 30 days, each with its countdown in the action colour. */
function Deadlines({ items, on }: { items: ContractDeadlineItemDto[]; on: string }) {
  const { t } = useTranslation();
  return (
    <section className="flex flex-col gap-2" aria-labelledby="today-deadlines" data-testid="today-deadlines">
      <h2 id="today-deadlines" className="ui-sec">
        <span>{t("today.deadlines")}</span>
        <span>{items.length}</span>
      </h2>
      {items.length === 0 ? (
        <p className="text-sm text-text-3">{t("today.deadlinesEmpty")}</p>
      ) : (
        <ul className="flex flex-col gap-2">
          {items.map((item) => (
            <DeadlineCard key={item.contractId} item={item} on={on} />
          ))}
        </ul>
      )}
    </section>
  );
}

/** What waits in the inbox, from the shell's counts, so Today asks nothing extra. */
function InboxSummary() {
  const { t } = useTranslation();
  const { openCards, unreadMessages } = useShellData();
  return (
    <section className="flex flex-col gap-2" aria-labelledby="today-inbox">
      <h2 id="today-inbox" className="ui-sec">
        <span>{t("today.inbox")}</span>
        <span>{openCards + unreadMessages}</span>
      </h2>
      <Link to="/inbox" className="card flex flex-col gap-1.5" data-testid="today-inbox">
        <span className="text-[15px] leading-snug font-semibold">
          {t("today.inboxCards", { count: openCards })} · {t("today.inboxMessages", { count: unreadMessages })}
        </span>
        <span className="ui-meta">
          <span>{t("today.inboxOpen")}</span>
        </span>
      </Link>
    </section>
  );
}
