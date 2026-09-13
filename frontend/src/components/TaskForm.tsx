import { useState, type FormEvent } from "react";
import { useTranslation } from "react-i18next";
import { api, unwrap } from "../api/client";
import type { TaskDto } from "../api/types";
import { useSession } from "../auth/SessionContext";
import { problemMessage } from "../lib/problem";
import { ErrorBox } from "./common";

interface Props {
  sourceDocumentId?: string;
  onCreated(task: TaskDto): void;
}

export function TaskForm({ sourceDocumentId, onCreated }: Props) {
  const { t } = useTranslation();
  const { members } = useSession();
  const [title, setTitle] = useState("");
  const [note, setNote] = useState("");
  const [ownerId, setOwnerId] = useState("");
  const [dueDate, setDueDate] = useState("");
  const [plannedDate, setPlannedDate] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  const onSubmit = async (event: FormEvent) => {
    event.preventDefault();
    setBusy(true);
    setError(null);
    try {
      const created = unwrap(
        await api.POST("/api/tasks", {
          body: {
            title,
            note,
            ownerId: ownerId || null,
            dueDate: dueDate || null,
            plannedDate: plannedDate || null,
            sourceDocumentId: sourceDocumentId ?? null,
          },
        }),
      );
      setTitle("");
      setNote("");
      setOwnerId("");
      setDueDate("");
      setPlannedDate("");
      onCreated(created);
    } catch (failure) {
      setError(problemMessage(t, failure));
    } finally {
      setBusy(false);
    }
  };

  return (
    <form className="card flex flex-col gap-3" onSubmit={onSubmit} data-testid="task-form">
      <h3 className="font-semibold">{sourceDocumentId ? t("documents.newTask") : t("tasks.new")}</h3>
      <label>
        <span className="label">{t("tasks.titleField")}</span>
        <input className="field" value={title} onChange={(e) => setTitle(e.target.value)} required maxLength={200} data-testid="task-title" />
      </label>
      <label>
        <span className="label">{t("tasks.note")}</span>
        <textarea className="field" rows={2} value={note} onChange={(e) => setNote(e.target.value)} maxLength={2000} />
      </label>
      <div className="grid gap-3 sm:grid-cols-3">
        <label>
          <span className="label">{t("tasks.owner")}</span>
          <select className="field" value={ownerId} onChange={(e) => setOwnerId(e.target.value)} data-testid="task-owner">
            <option value="">{t("tasks.unassigned")}</option>
            {members.map((m) => (
              <option key={m.id} value={m.id}>
                {m.displayName}
              </option>
            ))}
          </select>
        </label>
        <label>
          <span className="label">{t("tasks.dueDate")}</span>
          <input type="date" className="field" value={dueDate} onChange={(e) => setDueDate(e.target.value)} data-testid="task-due" />
        </label>
        <label>
          <span className="label">{t("tasks.plannedDate")}</span>
          <input type="date" className="field" value={plannedDate} onChange={(e) => setPlannedDate(e.target.value)} data-testid="task-planned" />
        </label>
      </div>
      <p className="text-xs text-stone-500">{t("tasks.dueHint")}</p>
      <ErrorBox message={error} />
      <div>
        <button type="submit" className="btn-primary" disabled={busy} data-testid="task-create">
          {t("tasks.create")}
        </button>
      </div>
    </form>
  );
}
