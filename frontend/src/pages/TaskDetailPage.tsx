import { useEffect, useState } from "react";
import { Link, useParams } from "react-router";
import { useTranslation } from "react-i18next";
import { api, unwrap } from "../api/client";
import type { TaskDto } from "../api/types";
import { TaskItem } from "../components/TaskItem";
import { ErrorBox, Loading, PageTitle } from "../components/common";
import { problemMessage } from "../lib/problem";

export function TaskDetailPage() {
  const { id = "" } = useParams();
  const { t } = useTranslation();
  const [task, setTask] = useState<TaskDto | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    void (async () => {
      try {
        setTask(unwrap(await api.GET("/api/tasks/{id}", { params: { path: { id } } })));
      } catch (failure) {
        setError(problemMessage(t, failure));
      }
    })();
  }, [id, t]);

  return (
    <>
      <p className="mb-2 text-sm">
        <Link to="/tasks" className="text-sky-800 hover:underline dark:text-sky-300">
          ← {t("tasks.title")}
        </Link>
      </p>
      <PageTitle>{task?.title ?? t("tasks.title")}</PageTitle>
      <ErrorBox message={error} />
      {!task && !error && <Loading />}
      {task && <TaskItem task={task} onChange={setTask} detailed />}
    </>
  );
}
