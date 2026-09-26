import { useState } from "react";
import { useTranslation } from "react-i18next";
import { api, toApiError, unwrap } from "../../api/client";
import type { TaskDto } from "../../api/types";
import { problemMessage } from "../../lib/problem";

type Call = () => Promise<{ data?: TaskDto; error?: unknown; response: Response }>;

/**
 * Runs task writes. Every write carries the version the member saw; a conflict reloads the task instead of
 * overwriting someone else's change, and says so.
 */
export function useTaskMutation(task: TaskDto, onChange: (task: TaskDto) => void) {
  const { t } = useTranslation();
  const [error, setError] = useState<string | null>(null);
  const [conflict, setConflict] = useState(false);
  const [busy, setBusy] = useState(false);

  const run = async (call: Call): Promise<TaskDto | null> => {
    setBusy(true);
    setError(null);
    setConflict(false);
    try {
      const updated = unwrap(await call());
      onChange(updated);
      return updated;
    } catch (failure) {
      if (toApiError(failure).status === 409) {
        setConflict(true);
        const fresh = await api.GET("/api/tasks/{id}", { params: { path: { id: task.id } } });
        if (fresh.data) onChange(fresh.data);
      } else {
        setError(problemMessage(t, failure));
      }
      return null;
    } finally {
      setBusy(false);
    }
  };

  const body = { expectedVersion: task.version };
  const path = { params: { path: { id: task.id } }, body };
  return {
    busy,
    error,
    conflict,
    run,
    complete: () => run(() => api.POST("/api/tasks/{id}/complete", path)),
    reopen: () => run(() => api.POST("/api/tasks/{id}/reopen", path)),
  };
}
