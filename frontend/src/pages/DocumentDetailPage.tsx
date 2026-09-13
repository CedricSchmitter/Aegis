import { useCallback, useEffect, useState } from "react";
import { Link, useParams } from "react-router";
import { useTranslation } from "react-i18next";
import { api, unwrap } from "../api/client";
import type { DocumentDto, TaskDto } from "../api/types";
import { useSession } from "../auth/SessionContext";
import { SubjectList } from "../components/SubjectList";
import { TaskForm } from "../components/TaskForm";
import { TaskItem } from "../components/TaskItem";
import { Badge, EmptyState, ErrorBox, InfoBox, Loading, PageTitle } from "../components/common";
import { formatBytes, formatDate, formatDateTime } from "../lib/format";
import { problemMessage } from "../lib/problem";

export function DocumentDetailPage() {
  const { id = "" } = useParams();
  const { t, i18n } = useTranslation();
  const { members, memberName } = useSession();
  const [document, setDocument] = useState<DocumentDto | null>(null);
  const [tasks, setTasks] = useState<TaskDto[]>([]);
  const [error, setError] = useState<string | null>(null);
  const [message, setMessage] = useState<string | null>(null);
  const [editingSubjects, setEditingSubjects] = useState(false);
  const [subjectDraft, setSubjectDraft] = useState<string[]>([]);
  const [showTaskForm, setShowTaskForm] = useState(false);

  const load = useCallback(async () => {
    try {
      const [doc, linked] = await Promise.all([
        api.GET("/api/documents/{id}", { params: { path: { id } } }),
        api.GET("/api/tasks", { params: { query: { documentId: id } } }),
      ]);
      setDocument(unwrap(doc));
      setTasks(unwrap(linked));
      setError(null);
    } catch (failure) {
      setError(problemMessage(t, failure));
    }
  }, [id, t]);

  useEffect(() => {
    void load();
  }, [load]);

  useEffect(() => {
    if (!document || (document.processing.state !== "RECEIVED" && document.processing.state !== "SUBMITTED")) return;
    const timer = window.setTimeout(() => void load(), 4000);
    return () => window.clearTimeout(timer);
  }, [document, load]);

  const retry = async () => {
    try {
      setDocument(unwrap(await api.POST("/api/documents/{id}/retry", { params: { path: { id } } })));
      setMessage(t("documents.retryDone"));
    } catch (failure) {
      setError(problemMessage(t, failure));
    }
  };

  const saveSubjects = async () => {
    if (!document) return;
    try {
      const subjects = subjectDraft.map((value) => {
        const [kind, memberId] = value.split(":");
        return { kind, memberId: memberId ?? null };
      });
      setDocument(unwrap(await api.PUT("/api/documents/{id}/subjects", { params: { path: { id } }, body: { subjects, expectedVersion: document.version } })));
      setEditingSubjects(false);
    } catch (failure) {
      setError(problemMessage(t, failure));
      await load();
    }
  };

  if (!document && !error) return <Loading />;
  if (!document) return <ErrorBox message={error} />;
  const p = document.processing;

  return (
    <>
      <p className="mb-2 text-sm">
        <Link to="/documents" className="text-sky-800 hover:underline dark:text-sky-300">
          ← {t("documents.title")}
        </Link>
      </p>
      <PageTitle actions={<Badge state={p.state} label={t(`documents.stateShort.${p.state}`)} />}>{document.title}</PageTitle>
      <ErrorBox message={error} />
      {message && <InfoBox>{message}</InfoBox>}

      <section className="card mb-4 flex flex-col gap-2" aria-label={t("documents.state." + p.state)}>
        <p className="font-medium" data-testid="processing-state">{t(`documents.state.${p.state}`)}</p>
        {p.retryScheduled && (
          <p className="text-sm text-stone-600 dark:text-stone-400">
            {t("documents.retrying")} · {p.nextAttemptAt && t("documents.nextAttempt", { date: formatDateTime(p.nextAttemptAt, i18n.language) })}
          </p>
        )}
        {p.attempts > 0 && <p className="text-sm text-stone-600 dark:text-stone-400">{t("documents.attempts", { count: p.attempts })}</p>}
        {p.lastErrorCode && (
          <p className="text-sm text-red-700 dark:text-red-300">
            {t("documents.lastError", { message: t(`errors.${p.lastErrorCode}`, { defaultValue: p.lastErrorMessage ?? p.lastErrorCode }) })}
          </p>
        )}
        {p.state === "ARCHIVED" && (
          <p className="text-sm text-stone-600 dark:text-stone-400">
            {t("documents.paperlessId", { id: p.paperlessDocumentId })} · {t("documents.archivedAt", { date: formatDateTime(p.archivedAt, i18n.language) })}
          </p>
        )}
        <div className="flex flex-wrap gap-2">
          {document.originalAvailable ? (
            <>
              <a className="btn-primary" href={`/api/documents/${document.id}/original?inline=true`} target="_blank" rel="noreferrer" data-testid="open-original">
                {t("documents.original")}
              </a>
              <a className="btn-secondary" href={`/api/documents/${document.id}/original`} data-testid="download-original">
                {t("documents.download")}
              </a>
            </>
          ) : (
            <p className="text-sm text-stone-500">{t("documents.originalUnavailable")}</p>
          )}
          {p.state === "FAILED" && (
            <button type="button" className="btn-secondary" onClick={retry} data-testid="retry-archival">
              {t("documents.retry")}
            </button>
          )}
        </div>
      </section>

      <section className="card mb-4 flex flex-col gap-2">
        <p className="text-sm text-stone-600 dark:text-stone-400">
          {t("documents.uploadedBy", { name: memberName(document.uploadedBy) ?? t("app.unknownMember"), date: formatDateTime(document.uploadedAt, i18n.language) })}
        </p>
        <p className="text-sm">
          {document.originalFilename} · {formatBytes(document.sizeBytes, t, i18n.language)}
        </p>
        <p className="break-all font-mono text-xs text-stone-500" data-testid="checksum">
          {t("documents.checksum")}: {document.checksumSha256}
        </p>
        <div className="flex flex-wrap items-center gap-2">
          <span className="text-sm font-medium">{t("documents.subjects")}:</span>
          <SubjectList subjects={document.subjects} />
          {!editingSubjects && (
            <button
              type="button"
              className="btn-ghost"
              onClick={() => {
                setSubjectDraft(document.subjects.map((s) => (s.kind === "MEMBER" ? `MEMBER:${s.memberId}` : s.kind)));
                setEditingSubjects(true);
              }}
            >
              {t("documents.editSubjects")}
            </button>
          )}
        </div>
        {editingSubjects && (
          <div className="flex flex-wrap items-center gap-3">
            {["HOUSEHOLD", "HOUSE", ...members.map((m) => `MEMBER:${m.id}`)].map((value) => (
              <label key={value} className="flex items-center gap-2 text-sm">
                <input
                  type="checkbox"
                  checked={subjectDraft.includes(value)}
                  onChange={() => setSubjectDraft((d) => (d.includes(value) ? d.filter((x) => x !== value) : [...d, value]))}
                />
                {value.startsWith("MEMBER:") ? (memberName(value.substring(7)) ?? value) : t(`subjects.${value}`)}
              </label>
            ))}
            <button type="button" className="btn-primary" onClick={saveSubjects}>{t("app.save")}</button>
            <button type="button" className="btn-ghost" onClick={() => setEditingSubjects(false)}>{t("app.cancel")}</button>
          </div>
        )}
      </section>

      <section aria-labelledby="linked-tasks">
        <div className="mb-2 flex items-center justify-between">
          <h2 id="linked-tasks" className="text-lg font-semibold">
            {t("documents.linkedTasks")}
          </h2>
          <button type="button" className="btn-secondary" onClick={() => setShowTaskForm((v) => !v)} data-testid="new-task-toggle">
            {t("documents.newTask")}
          </button>
        </div>
        {showTaskForm && (
          <div className="mb-3">
            <TaskForm
              sourceDocumentId={document.id}
              onCreated={(task) => {
                setTasks((current) => [...current, task]);
                setShowTaskForm(false);
                setMessage(t("tasks.created"));
              }}
            />
          </div>
        )}
        {tasks.length === 0 ? (
          <EmptyState>{t("documents.noLinkedTasks")}</EmptyState>
        ) : (
          <div className="flex flex-col gap-2">
            {tasks.map((task) => (
              <TaskItem key={task.id} task={task} onChange={(updated) => setTasks((current) => current.map((x) => (x.id === updated.id ? updated : x)))} />
            ))}
          </div>
        )}
      </section>
      <p className="mt-4 text-xs text-stone-500">{formatDate(document.uploadedAt, i18n.language)}</p>
    </>
  );
}
