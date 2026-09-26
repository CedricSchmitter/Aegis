import { useEffect, useState } from "react";
import { useParams } from "react-router";
import { useTranslation } from "react-i18next";
import { api, unwrap } from "../api/client";
import type { TaskDto } from "../api/types";
import { BackLink, ErrorBox, Loading } from "../components/common";
import { TaskDetail } from "../components/tasks/TaskDetail";
import { useTrashUndo } from "../components/TrashControls";
import { problemMessage } from "../lib/problem";

export function TaskDetailPage() {
  const { id = "" } = useParams();
  const { t } = useTranslation();
  const [task, setTask] = useState<TaskDto | null>(null);
  const [error, setError] = useState<string | null>(null);
  const { notify, toast } = useTrashUndo<TaskDto>();
  const restore = async (trashed: TaskDto) => {
    try {
      setTask(
        unwrap(await api.POST("/api/tasks/{id}/restore", { params: { path: { id: trashed.id } }, body: { expectedVersion: trashed.version } })),
      );
    } catch (failure) {
      setError(problemMessage(t, failure));
    }
  };

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
    <div className="flex max-w-2xl flex-col gap-4">
      <BackLink to="/tasks">{t("tasks.title")}</BackLink>
      <ErrorBox message={error} />
      {!task && !error && <Loading />}
      {task && (
        <TaskDetail
          task={task}
          onChange={setTask}
          onTrashed={(trashed) => notify(trashed, () => void restore(trashed))}
          heading={(title) => <h1 className="text-2xl leading-tight font-bold break-words lg:text-[28px]">{title}</h1>}
        />
      )}
      {toast}
    </div>
  );
}
