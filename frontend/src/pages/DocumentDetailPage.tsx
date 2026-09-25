import { useCallback, useEffect, useState } from "react";
import { Link, useParams } from "react-router";
import { useTranslation } from "react-i18next";
import { api, toApiError, unwrap } from "../api/client";
import type {
  CategoryDto,
  ContractSummaryDto,
  DocumentDto,
  Facet,
  LabelChipDto,
  LabelDto,
  TaskDto,
} from "../api/types";
import { useSession } from "../auth/SessionContext";
import { AnalysisPanel } from "../components/AnalysisPanel";
import { FolderSelect } from "../components/FolderSelect";
import { LabelChips } from "../components/LabelChips";
import { LabelEditor } from "../components/LabelEditor";
import { PlacementSuggestion } from "../components/PlacementSuggestion";
import { SubjectList } from "../components/SubjectList";
import { TaskForm } from "../components/TaskForm";
import { TextRecognitionHint } from "../components/TextRecognitionHint";
import { TaskItem } from "../components/TaskItem";
import { MoveToTrashButton, TrashBanner } from "../components/TrashControls";
import { Badge, EmptyState, ErrorBox, InfoBox, Loading, PageTitle } from "../components/common";
import { formatBytes, formatDate, formatDateTime } from "../lib/format";
import { subjectChoices, subjectKey, suggestions } from "../lib/labels";
import { problemMessage } from "../lib/problem";
import { searchablePdf } from "../lib/searchablePdf";
import { awaitingTextCheck } from "../lib/textRecognition";

export function DocumentDetailPage() {
  const { id = "" } = useParams();
  const { t, i18n } = useTranslation();
  const { persons, memberName } = useSession();
  const [document, setDocument] = useState<DocumentDto | null>(null);
  const [tasks, setTasks] = useState<TaskDto[]>([]);
  const [contracts, setContracts] = useState<ContractSummaryDto[]>([]);
  const [categories, setCategories] = useState<CategoryDto[]>([]);
  const [labels, setLabels] = useState<LabelDto[]>([]);
  const [error, setError] = useState<string | null>(null);
  const [message, setMessage] = useState<string | null>(null);
  const [editingSubjects, setEditingSubjects] = useState(false);
  const [subjectDraft, setSubjectDraft] = useState<string[]>([]);
  const [editingLabels, setEditingLabels] = useState(false);
  const [editingTitle, setEditingTitle] = useState(false);
  const [titleDraft, setTitleDraft] = useState("");
  const [folderDraft, setFolderDraft] = useState<string | null>(null);
  const [showTaskForm, setShowTaskForm] = useState(false);
  const [trashBusy, setTrashBusy] = useState(false);

  const load = useCallback(async () => {
    try {
      const [doc, linked, related, tree, vocabulary] = await Promise.all([
        api.GET("/api/documents/{id}", { params: { path: { id } } }),
        api.GET("/api/tasks", { params: { query: { documentId: id } } }),
        api.GET("/api/contracts", { params: { query: { documentId: id } } }),
        api.GET("/api/categories"),
        api.GET("/api/labels"),
      ]);
      const loaded = unwrap(doc);
      setDocument(loaded);
      setFolderDraft(loaded.classification.categoryId ?? null);
      setTasks(unwrap(linked));
      setContracts(unwrap(related));
      setCategories(unwrap(tree).categories);
      setLabels(unwrap(vocabulary));
      setError(null);
    } catch (failure) {
      setError(problemMessage(t, failure));
    }
  }, [id, t]);

  useEffect(() => {
    void load();
  }, [load]);

  useEffect(() => {
    if (!document) return;
    const archiving = document.processing.state === "RECEIVED" || document.processing.state === "SUBMITTED";
    const analyzing = document.analysis.state === "PENDING" || document.analysis.state === "WAITING_FOR_ARCHIVE";
    if (!archiving && !analyzing && !awaitingTextCheck(document)) return;
    const timer = window.setTimeout(() => void load(), 4000);
    return () => window.clearTimeout(timer);
  }, [document, load]);

  /** Trash and restore carry the version the member saw; a concurrent change reloads instead of guessing. */
  const changeTrash = async (action: "trash" | "restore") => {
    if (!document) return;
    setTrashBusy(true);
    setError(null);
    setMessage(null);
    try {
      const options = { params: { path: { id } }, body: { expectedVersion: document.version } };
      setDocument(
        unwrap(
          action === "trash"
            ? await api.POST("/api/documents/{id}/trash", options)
            : await api.POST("/api/documents/{id}/restore", options),
        ),
      );
      setMessage(t(action === "trash" ? "trash.moved" : "trash.restored"));
      if (action === "restore") await load();
    } catch (failure) {
      setError(toApiError(failure).status === 409 ? t("trash.changedMeanwhile") : problemMessage(t, failure));
      await load();
    } finally {
      setTrashBusy(false);
    }
  };

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
      setDocument(
        unwrap(
          await api.PUT("/api/documents/{id}/subjects", {
            params: { path: { id } },
            body: { subjects, expectedVersion: document.version },
          }),
        ),
      );
      setEditingSubjects(false);
    } catch (failure) {
      setError(problemMessage(t, failure));
      await load();
    }
  };

  const saveTitle = async () => {
    if (!document) return;
    try {
      setDocument(
        unwrap(
          await api.PUT("/api/documents/{id}/title", {
            params: { path: { id } },
            body: { title: titleDraft, expectedVersion: document.version },
          }),
        ),
      );
      setEditingTitle(false);
      setMessage(t("documents.renamed"));
    } catch (failure) {
      setError(problemMessage(t, failure));
      await load();
    }
  };

  const saveFolder = async () => {
    if (!document) return;
    try {
      setDocument(
        unwrap(
          await api.PUT("/api/documents/{id}/category", {
            params: { path: { id } },
            body: { categoryId: folderDraft, expectedVersion: document.classification.version },
          }),
        ),
      );
      setMessage(t("documents.folderSaved"));
      setError(null);
    } catch (failure) {
      setError(problemMessage(t, failure));
      await load();
    }
  };

  const saveLabels = async (labelIds: string[]) => {
    if (!document) return;
    try {
      setDocument(
        unwrap(
          await api.PUT("/api/documents/{id}/labels", {
            params: { path: { id } },
            body: { labelIds, expectedVersion: document.classification.version },
          }),
        ),
      );
      setEditingLabels(false);
      setMessage(t("documents.labelsSaved"));
      setError(null);
    } catch (failure) {
      setError(problemMessage(t, failure));
      await load();
    }
  };

  const createLabel = async (facet: Facet, name: string): Promise<LabelDto | null> => {
    try {
      const created = unwrap(await api.POST("/api/labels", { body: { facet, name } }));
      setLabels((current) => [...current, created]);
      return created;
    } catch (failure) {
      setError(problemMessage(t, failure));
      return null;
    }
  };

  const review = async (chip: LabelChipDto, decision: "accept" | "reject") => {
    if (!document) return;
    try {
      const path = decision === "accept"
        ? "/api/documents/{id}/labels/{labelId}/accept"
        : "/api/documents/{id}/labels/{labelId}/reject";
      setDocument(
        unwrap(
          await api.POST(path, {
            params: { path: { id, labelId: chip.labelId } },
            body: { expectedVersion: document.classification.version },
          }),
        ),
      );
      setError(null);
    } catch (failure) {
      setError(problemMessage(t, failure));
      await load();
    }
  };

  if (!document && !error) return <Loading />;
  if (!document) return <ErrorBox message={error} />;
  const p = document.processing;
  const folderName = (folderId: string) => categories.find((category) => category.id === folderId)?.name ?? "";
  const pending = suggestions(document.classification.labels);
  const trashed = !!document.trash;
  const searchable = searchablePdf(document);

  return (
    <>
      <p className="mb-2 text-sm">
        <Link to="/documents" className="text-sky-800 hover:underline dark:text-sky-300">
          ← {t("documents.title")}
        </Link>
      </p>
      <PageTitle actions={<Badge state={p.state} label={t(`documents.stateShort.${p.state}`)} />}>
        {document.title}
      </PageTitle>
      <ErrorBox message={error} />
      {message && <InfoBox>{message}</InfoBox>}
      {document.trash && (
        <TrashBanner trash={document.trash} busy={trashBusy} onRestore={() => void changeTrash("restore")} />
      )}

      <section className="card mb-4 flex flex-col gap-2" aria-label={t(`documents.state.${p.state}`)}>
        <p className="font-medium" data-testid="processing-state">
          {t(`documents.state.${p.state}`)}
        </p>
        {p.retryScheduled && (
          <p className="text-sm text-stone-600 dark:text-stone-400">
            {t("documents.retrying")} ·{" "}
            {p.nextAttemptAt && t("documents.nextAttempt", { date: formatDateTime(p.nextAttemptAt, i18n.language) })}
          </p>
        )}
        {p.attempts > 0 && (
          <p className="text-sm text-stone-600 dark:text-stone-400">
            {t("documents.attempts", { count: p.attempts })}
          </p>
        )}
        {p.lastErrorCode && (
          <p className="text-sm text-red-700 dark:text-red-300">
            {t("documents.lastError", {
              message: t(`errors.${p.lastErrorCode}`, { defaultValue: p.lastErrorMessage ?? p.lastErrorCode }),
            })}
          </p>
        )}
        {p.state === "ARCHIVED" && (
          <p className="text-sm text-stone-600 dark:text-stone-400">
            {t("documents.paperlessId", { id: p.paperlessDocumentId })} ·{" "}
            {t("documents.archivedAt", { date: formatDateTime(p.archivedAt, i18n.language) })}
          </p>
        )}
        <TextRecognitionHint document={document} />
        {(searchable === "photo" || searchable === "photoPending") && (
          <p className="text-sm text-stone-600 dark:text-stone-400" data-testid="photo-hint">
            {searchable === "photo" ? t("documents.archivePdfHint") : t("documents.archivePdfPending")}
            {document.contentType === "image/heic" && ` ${t("documents.heicHint")}`}
          </p>
        )}
        {searchable === "scan" && (
          <p className="text-sm text-stone-600 dark:text-stone-400" data-testid="scan-hint">
            {t("documents.archivePdfScanHint")}
          </p>
        )}
        <div className="flex flex-wrap gap-2">
          {document.originalAvailable ? (
            <>
              <a
                className="btn-primary"
                href={`/api/documents/${document.id}/original?inline=true`}
                target="_blank"
                rel="noreferrer"
                data-testid="open-original"
              >
                {t("documents.original")}
              </a>
              <a className="btn-secondary" href={`/api/documents/${document.id}/original`} data-testid="download-original">
                {t("documents.download")}
              </a>
            </>
          ) : (
            <p className="text-sm text-stone-500">{t("documents.originalUnavailable")}</p>
          )}
          {(searchable === "photo" || searchable === "scan") && (
            <a
              className="btn-secondary"
              href={`/api/documents/${document.id}/archive-pdf?inline=true`}
              target="_blank"
              rel="noreferrer"
              data-testid="open-archive-pdf"
            >
              {t("documents.archivePdf")}
            </a>
          )}
          {p.state === "FAILED" && !trashed && (
            <button type="button" className="btn-secondary" onClick={retry} data-testid="retry-archival">
              {t("documents.retry")}
            </button>
          )}
        </div>
      </section>

      {p.state !== "FAILED" && !trashed && (
        <AnalysisPanel
          document={document}
          onChange={setDocument}
          onError={setError}
          onMessage={setMessage}
          reload={load}
        />
      )}

      <section className="card mb-4 flex flex-col gap-3" aria-label={t("documents.folder")}>
        <div className="flex flex-wrap items-center gap-2">
          <span className="text-sm font-medium">{t("documents.folder")}:</span>
          <span className="text-sm" data-testid="document-folder">
            🗂 {document.classification.path.map((step) => step.name).join(" / ") || t("documents.unsorted")}
          </span>
        </div>
        {!trashed && (
          <div className="flex flex-wrap items-end gap-2">
            <FolderSelect
              categories={categories}
              value={folderDraft}
              onChange={setFolderDraft}
              label={t("documents.chooseFolder")}
              id="document-folder-select"
            />
            <button type="button" className="btn-secondary" onClick={saveFolder} data-testid="save-folder">
              {t("app.save")}
            </button>
          </div>
        )}

        {!trashed && (
          <PlacementSuggestion
            document={document}
            categories={categories}
            onChange={setDocument}
            onError={setError}
            onMessage={setMessage}
            reload={load}
          />
        )}

        <div className="flex flex-wrap items-center gap-2">
          <span className="text-sm font-medium">{t("documents.labels")}:</span>
          <LabelChips
            chips={document.classification.labels}
            folderName={folderName}
            onAccept={trashed ? undefined : (chip) => void review(chip, "accept")}
            onReject={trashed ? undefined : (chip) => void review(chip, "reject")}
          />
          {document.classification.labels.length === 0 && (
            <span className="text-xs text-stone-500">{t("subjects.none")}</span>
          )}
          {!editingLabels && !trashed && (
            <button type="button" className="btn-ghost" onClick={() => setEditingLabels(true)} data-testid="edit-labels">
              {t("documents.editLabels")}
            </button>
          )}
        </div>
        <p className="text-xs text-stone-500">{t("documents.inheritedFromFolder")}</p>
        {pending.length > 0 && <p className="text-xs text-stone-500">{t("documents.confidenceHint")}</p>}
        {editingLabels && (
          <LabelEditor
            key={document.classification.version}
            chips={document.classification.labels}
            labels={labels}
            onSave={saveLabels}
            onCreate={createLabel}
          />
        )}
      </section>

      <section className="card mb-4 flex flex-col gap-2">
        <p className="text-sm text-stone-600 dark:text-stone-400">
          {t("documents.uploadedBy", {
            name: memberName(document.uploadedBy) ?? t("app.unknownMember"),
            date: formatDateTime(document.uploadedAt, i18n.language),
          })}
        </p>
        <p className="text-sm">
          {document.originalFilename} · {formatBytes(document.sizeBytes, t, i18n.language)}
        </p>
        <p className="break-all font-mono text-xs text-stone-500" data-testid="checksum">
          {t("documents.checksum")}: {document.checksumSha256}
        </p>
        <div className="flex flex-wrap items-center gap-2">
          {editingTitle ? (
            <>
              <label className="label mb-0" htmlFor="title-draft">
                {t("documents.renameTitle")}
              </label>
              <input
                id="title-draft"
                className="field max-w-sm"
                value={titleDraft}
                onChange={(event) => setTitleDraft(event.target.value)}
              />
              <button type="button" className="btn-primary" onClick={saveTitle} data-testid="save-title">
                {t("app.save")}
              </button>
              <button type="button" className="btn-ghost" onClick={() => setEditingTitle(false)}>
                {t("app.cancel")}
              </button>
            </>
          ) : (
            !trashed && (
              <button
                type="button"
                className="btn-ghost"
                onClick={() => {
                  setTitleDraft(document.title);
                  setEditingTitle(true);
                }}
                data-testid="rename-document"
              >
                {t("documents.renameTitle")}
              </button>
            )
          )}
        </div>
        <div className="flex flex-wrap items-center gap-2">
          <span className="text-sm font-medium">{t("documents.subjects")}:</span>
          <SubjectList subjects={document.subjects} />
          {document.classification.folderSubjects.length > 0 && (
            <span className="text-xs text-stone-500">
              🗂 <SubjectList subjects={document.classification.folderSubjects} />
            </span>
          )}
          {!editingSubjects && !trashed && (
            <button
              type="button"
              className="btn-ghost"
              onClick={() => {
                setSubjectDraft(
                  document.subjects.map((subject) =>
                    subject.kind === "MEMBER" ? `MEMBER:${subject.memberId}` : subject.kind,
                  ),
                );
                setEditingSubjects(true);
              }}
            >
              {t("documents.editSubjects")}
            </button>
          )}
        </div>
        {editingSubjects && (
          <div className="flex flex-wrap items-center gap-3">
            {subjectChoices(persons, document.subjects.map(subjectKey)).map((value) => (
              <label key={value} className="flex items-center gap-2 text-sm">
                <input
                  type="checkbox"
                  checked={subjectDraft.includes(value)}
                  onChange={() =>
                    setSubjectDraft((draft) =>
                      draft.includes(value) ? draft.filter((entry) => entry !== value) : [...draft, value],
                    )
                  }
                />
                {value.startsWith("MEMBER:") ? (memberName(value.substring(7)) ?? value) : t(`subjects.${value}`)}
              </label>
            ))}
            <button type="button" className="btn-primary" onClick={saveSubjects}>
              {t("app.save")}
            </button>
            <button type="button" className="btn-ghost" onClick={() => setEditingSubjects(false)}>
              {t("app.cancel")}
            </button>
          </div>
        )}
      </section>

      {contracts.length > 0 && (
        <section className="mb-4" aria-labelledby="linked-contracts">
          <h2 id="linked-contracts" className="mb-2 text-lg font-semibold">
            {t("documents.linkedContracts")}
          </h2>
          <ul className="flex flex-col gap-2">
            {contracts.map((contract) => (
              <li key={contract.id} className="card flex flex-wrap items-center justify-between gap-2">
                <Link
                  to={`/contracts/${contract.id}`}
                  className="font-medium text-sky-800 hover:underline dark:text-sky-300"
                >
                  {contract.name}
                </Link>
                <span className="text-sm">
                  {contract.deadline.determined && contract.deadline.deadline
                    ? t("contracts.deadline.cancelBy", {
                        date: formatDate(contract.deadline.deadline, i18n.language),
                      })
                    : t("contracts.deadline.none")}
                </span>
              </li>
            ))}
          </ul>
        </section>
      )}

      <section aria-labelledby="linked-tasks">
        <div className="mb-2 flex items-center justify-between">
          <h2 id="linked-tasks" className="text-lg font-semibold">
            {t("documents.linkedTasks")}
          </h2>
          {!trashed && (
            <button
              type="button"
              className="btn-secondary"
              onClick={() => setShowTaskForm((value) => !value)}
              data-testid="new-task-toggle"
            >
              {t("documents.newTask")}
            </button>
          )}
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
              <TaskItem
                key={task.id}
                task={task}
                onChange={(updated) =>
                  setTasks((current) => current.map((entry) => (entry.id === updated.id ? updated : entry)))
                }
              />
            ))}
          </div>
        )}
      </section>
      {!trashed && (
        <section className="mt-6 flex flex-col gap-2" aria-labelledby="document-trash">
          <h2 id="document-trash" className="text-lg font-semibold">
            {t("trash.section")}
          </h2>
          <p className="text-sm text-stone-600 dark:text-stone-400">{t("trash.documentHint")}</p>
          <div>
            <MoveToTrashButton busy={trashBusy} onConfirm={() => void changeTrash("trash")} testId="trash-document" />
          </div>
        </section>
      )}
      <p className="mt-4 text-xs text-stone-500">{formatDate(document.uploadedAt, i18n.language)}</p>
    </>
  );
}
