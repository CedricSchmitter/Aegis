import { useCallback, useEffect, useState } from "react";
import { useTranslation } from "react-i18next";
import { api, unwrap } from "../api/client";
import type { TaskDto } from "../api/types";
import { TaskForm } from "../components/TaskForm";
import { TaskItem } from "../components/TaskItem";
import { EmptyState, ErrorBox, Loading, PageTitle } from "../components/common";
import { problemMessage } from "../lib/problem";

export function TasksPage() {
  const { t } = useTranslation();
  const [tasks, setTasks] = useState<TaskDto[] | null>(null);
  const [includeDone, setIncludeDone] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [showForm, setShowForm] = useState(false);

  const load = useCallback(async () => {
    try {
      setTasks(unwrap(await api.GET("/api/tasks", { params: { query: { includeDone } } })));
      setError(null);
    } catch (failure) {
      setError(problemMessage(t, failure));
    }
  }, [includeDone, t]);

  useEffect(() => {
    void load();
  }, [load]);

  const replace = (updated: TaskDto) =>
    setTasks((current) => (current ?? []).map((task) => (task.id === updated.id ? updated : task)).filter((task) => includeDone || task.status === "OPEN"));

  return (
    <>
      <PageTitle
        actions={
          <div className="flex items-center gap-3">
            <label className="flex items-center gap-2 text-sm">
              <input type="checkbox" checked={includeDone} onChange={(e) => setIncludeDone(e.target.checked)} />
              {t("tasks.showDone")}
            </label>
            <button type="button" className="btn-primary" onClick={() => setShowForm((v) => !v)} data-testid="new-task-toggle">
              {t("tasks.new")}
            </button>
          </div>
        }
      >
        {t("tasks.title")}
      </PageTitle>
      {showForm && (
        <div className="mb-4">
          <TaskForm
            onCreated={(task) => {
              setTasks((current) => [task, ...(current ?? [])]);
              setShowForm(false);
            }}
          />
        </div>
      )}
      <ErrorBox message={error} />
      {!tasks && !error && <Loading />}
      {tasks && tasks.length === 0 && <EmptyState>{t("tasks.empty")}</EmptyState>}
      {tasks && (
        <div className="flex flex-col gap-2">
          {tasks.map((task) => (
            <TaskItem key={task.id} task={task} onChange={replace} />
          ))}
        </div>
      )}
    </>
  );
}
