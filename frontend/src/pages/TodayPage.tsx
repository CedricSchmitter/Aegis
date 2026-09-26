import { useCallback, useEffect, useState } from "react";
import { useTranslation } from "react-i18next";
import { api, unwrap } from "../api/client";
import type { TaskDto, TodayResponse } from "../api/types";
import { TaskItem } from "../components/TaskItem";
import { StatusLine } from "../components/shell/StatusIndicator";
import { EmptyState, ErrorBox, Loading, PageTitle } from "../components/common";
import { formatDate } from "../lib/format";
import { problemMessage } from "../lib/problem";

export function TodayPage() {
  const { t, i18n } = useTranslation();
  const [today, setToday] = useState<TodayResponse | null>(null);
  const [error, setError] = useState<string | null>(null);

  const load = useCallback(async () => {
    try {
      setToday(unwrap(await api.GET("/api/today")));
      setError(null);
    } catch (failure) {
      setError(problemMessage(t, failure));
    }
  }, [t]);

  useEffect(() => {
    void load();
  }, [load]);

  const replace = (updated: TaskDto) => {
    void load();
    void updated;
  };

  const buckets: { key: keyof Omit<TodayResponse, "date" | "calendar">; label: string }[] = [
    { key: "overdue", label: t("today.overdue") },
    { key: "dueToday", label: t("today.dueToday") },
    { key: "plannedToday", label: t("today.plannedToday") },
    { key: "upcoming", label: t("today.upcoming") },
    { key: "unassigned", label: t("today.unassigned") },
  ];

  return (
    <>
      <PageTitle>
        {t("today.title")}
        {today && <span className="ml-2 text-base font-normal text-stone-500">{formatDate(today.date, i18n.language)}</span>}
      </PageTitle>
      {/* The status line replaces the former Status tab (issue #83 F5); it opens the status page. */}
      <div className="mb-4">
        <StatusLine />
      </div>
      <ErrorBox message={error} />
      {!today && !error && <Loading />}
      {today && (
        <div className="flex flex-col gap-4">
          {buckets.every((b) => today[b.key].length === 0) && <EmptyState>{t("today.empty")}</EmptyState>}
          {buckets.map(
            (bucket) =>
              today[bucket.key].length > 0 && (
                <section key={bucket.key} aria-labelledby={`bucket-${bucket.key}`} data-testid={`bucket-${bucket.key}`}>
                  <h2 id={`bucket-${bucket.key}`} className="mb-2 text-lg font-semibold">
                    {bucket.label} <span className="text-stone-500">({today[bucket.key].length})</span>
                  </h2>
                  <div className="flex flex-col gap-2">
                    {today[bucket.key].map((task) => (
                      <TaskItem key={task.id} task={task} onChange={replace} />
                    ))}
                  </div>
                </section>
              ),
          )}
        </div>
      )}
    </>
  );
}
