import { useCallback, useState } from "react";
import { useTranslation } from "react-i18next";
import { api, unwrap } from "../../api/client";
import type { TaskDto } from "../../api/types";
import { problemMessage } from "../../lib/problem";
import { Toast } from "../ui";

/**
 * The message after a task was completed with one tap, with undo: a mistap on a phone reopens the task instead of
 * losing it from Today. Undo carries the completed version, so it never reopens a task someone changed since.
 */
export function useCompletionToast(onChange: (task: TaskDto) => void, onError: (message: string) => void) {
  const { t } = useTranslation();
  const [completed, setCompleted] = useState<TaskDto | null>(null);

  const undo = async (task: TaskDto) => {
    try {
      onChange(
        unwrap(
          await api.POST("/api/tasks/{id}/reopen", {
            params: { path: { id: task.id } },
            body: { expectedVersion: task.version },
          }),
        ),
      );
    } catch (failure) {
      onError(problemMessage(t, failure));
    }
  };

  const dismiss = useCallback(() => setCompleted(null), []);
  const toast = completed && (
    <Toast key={`${completed.id}-${completed.version}`} title={t("tasks.completedToast")} onUndo={() => void undo(completed)} onDismiss={dismiss}>
      {t("tasks.completedToastHint")}
    </Toast>
  );
  return { notify: setCompleted, toast };
}
