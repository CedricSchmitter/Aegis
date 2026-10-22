import { useCallback, useEffect, useState, type ReactNode } from "react";
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
import { ChangeHistory } from "../components/ChangeHistory";
import { DocumentHead, DocumentPreview, DocumentViewer } from "../components/documents/DocumentHead";
import { SimilarDocuments } from "../components/documents/SimilarDocuments";
import { FolderSelect } from "../components/FolderSelect";
import { LabelChips } from "../components/LabelChips";
import { LabelEditor } from "../components/LabelEditor";
import { PlacementSuggestion } from "../components/PlacementSuggestion";
import { ContextPanelOrFlow } from "../components/shell/ContextPanel";
import { Icon } from "../components/shell/icons";
import { useDesktop } from "../components/shell/ShellData";
import { SubjectList } from "../components/SubjectList";
import { TaskForm } from "../components/TaskForm";
import { TextRecognitionHint } from "../components/TextRecognitionHint";
import { TaskCard } from "../components/tasks/TaskCard";
import { MoveToTrashButton, TrashBanner, useTrashUndo } from "../components/TrashControls";
import { Derivations } from "../components/pageEdit/Derivations";
import { ProcessingSteps } from "../components/ui";
import { EmptyState, ErrorBox, InfoBox, Loading } from "../components/common";
import { documentProgress } from "../lib/documentView";
import { formatBytes, formatDate, formatDateTime } from "../lib/format";
import type { HistoryEntry } from "../lib/history";
import { labelName, subjectChoices, subjectKey } from "../lib/labels";
import { problemMessage } from "../lib/problem";
import { searchablePdf } from "../lib/searchablePdf";
import { awaitingTextCheck } from "../lib/textRecognition";
import { pathOf } from "../lib/tree";

/** A titled block of the document view, headed by a mono section line like in the approved mockup. */
function Block({ id, title, action, children, testId }: { id: string; title: ReactNode; action?: ReactNode; children: ReactNode; testId?: string }) {
  return (
    <section className="flex flex-col gap-2.5" aria-labelledby={id} data-testid={testId}>
      <div className="ui-sec min-h-11">
        <h2 id={id} className="font-normal">
          {title}
        </h2>
        {action}
      </div>
      {children}
    </section>
  );
}

/**
 * One document: the folder path, the title and the file actions, then the preview on a phone or the
 * document itself on a desktop. Where it stands, the suggestions, the folder, labels, subjects, tasks and the trash
 * follow in the page on a phone and fill the context panel from 1280 px.
 */
export function DocumentDetailPage() {
  const { id = "" } = useParams();
  const { t, i18n } = useTranslation();
  const { persons, memberName } = useSession();
  const desktop = useDesktop();
  const [document, setDocument] = useState<DocumentDto | null>(null);
  const [tasks, setTasks] = useState<TaskDto[]>([]);
  const [contracts, setContracts] = useState<ContractSummaryDto[]>([]);
  const [categories, setCategories] = useState<CategoryDto[]>([]);
  const [labels, setLabels] = useState<LabelDto[]>([]);
  // An action's error stays until the next action; the reload after a failed action only replaces the load error.
  const [error, setError] = useState<string | null>(null);
  const [loadError, setLoadError] = useState<string | null>(null);
  const [message, setMessage] = useState<string | null>(null);
  const [editingSubjects, setEditingSubjects] = useState(false);
  const [subjectDraft, setSubjectDraft] = useState<string[]>([]);
  const [editingLabels, setEditingLabels] = useState(false);
  const [editingTitle, setEditingTitle] = useState(false);
  const [titleDraft, setTitleDraft] = useState("");
  const [folderDraft, setFolderDraft] = useState<string | null>(null);
  const [showTaskForm, setShowTaskForm] = useState(false);
  const [trashBusy, setTrashBusy] = useState(false);
  const { notify: notifyTrashed, dismiss: dismissTrashed, toast: trashToast } = useTrashUndo<DocumentDto>();

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
      setLoadError(null);
    } catch (failure) {
      setLoadError(problemMessage(t, failure));
    }
  }, [id, t]);

  useEffect(() => {
    void load();
  }, [load]);

  const loadHistory = useCallback(async (before: number | null) => {
    const query = before == null ? {} : { beforeSequence: before };
    const page = unwrap(await api.GET("/api/documents/{id}/history", { params: { path: { id }, query } }));
    return { recordingSince: page.recordingSince, changes: page.changes, next: page.nextBeforeSequence ?? null };
  }, [id]);

  /** An undo is bound to the values the change left, not to a version: no stale-version conflict to reload for. */
  const undoChange = async (entry: HistoryEntry) => {
    setError(null);
    const changed = unwrap(await api.POST("/api/documents/{id}/history/{changeId}/undo", { params: { path: { id, changeId: entry.id } } }));
    setDocument(changed);
    setFolderDraft(changed.classification.categoryId ?? null);
    setMessage(t("history.undoneMessage"));
  };

  useEffect(() => {
    if (!document) return;
    const archiving = document.processing.state === "RECEIVED" || document.processing.state === "SUBMITTED";
    const analyzing = document.analysis.state === "PENDING" || document.analysis.state === "WAITING_FOR_ARCHIVE";
    if (!archiving && !analyzing && !awaitingTextCheck(document)) return;
    const timer = window.setTimeout(() => void load(), 4000);
    return () => window.clearTimeout(timer);
  }, [document, load]);

  /**
   * Trash and restore carry the version the member saw; a concurrent change reloads instead of guessing. Moving to
   * the trash asks nothing and offers undo in a message. The reload after the action no longer holds the
   * buttons: should it hang, the member can still act, and a tap with the stale version only meets another conflict.
   */
  const changeTrash = async (action: "trash" | "restore", version: number) => {
    setTrashBusy(true);
    setError(null);
    setMessage(null);
    let reload = false;
    try {
      const options = { params: { path: { id } }, body: { expectedVersion: version } };
      const changed = unwrap(
        action === "trash"
          ? await api.POST("/api/documents/{id}/trash", options)
          : await api.POST("/api/documents/{id}/restore", options),
      );
      setDocument(changed);
      if (action === "trash") {
        notifyTrashed(changed, () => void changeTrash("restore", changed.version));
      } else {
        dismissTrashed();
        setMessage(t("trash.restored"));
        reload = true;
      }
    } catch (failure) {
      setError(toApiError(failure).status === 409 ? t("trash.changedMeanwhile") : problemMessage(t, failure));
      reload = true;
    } finally {
      setTrashBusy(false);
    }
    if (reload) await load();
  };

  const retry = async () => {
    setError(null);
    try {
      setDocument(unwrap(await api.POST("/api/documents/{id}/retry", { params: { path: { id } } })));
      setMessage(t("documents.retryDone"));
    } catch (failure) {
      setError(problemMessage(t, failure));
    }
  };

  const saveSubjects = async () => {
    if (!document) return;
    setError(null);
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
    setError(null);
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

  if (!document && !loadError) return <Loading />;
  if (!document) return <ErrorBox message={loadError} />;
  const p = document.processing;
  const folderName = (folderId: string) => categories.find((category) => category.id === folderId)?.name ?? "";
  const trashed = !!document.trash;
  const historyValue = (field: string, value: string | null | undefined) => {
    if (field === "folder") {
      if (!value) return t("documents.unsorted");
      const path = pathOf(categories, value);
      return path.length > 0 ? path.map((step) => step.name).join(" / ") : t("history.value.deletedFolder");
    }
    if (field === "filename") return value || t("history.value.generatedFilename");
    return value || t("history.value.none");
  };
  const historyLabel = (labelId: string) =>
    labelName(labels.find((label) => label.id === labelId), t) || t("history.value.unknownLabel");
  const searchable = searchablePdf(document);
  const progress = documentProgress(document);
  const hint = "text-sm text-text-3";
  // A desktop keeps the document page open next to the file. On a phone the file opens in place: a new window of the
  // installed app has no history, so the back button would close the app instead of returning to the document.
  const viewerTarget = desktop ? { target: "_blank", rel: "noreferrer" } : {};

  return (
    <div className="flex flex-col gap-4">
      <Link
        to="/documents"
        className="-ml-1 flex min-h-11 items-center gap-1.5 self-start font-mono text-xs tracking-[0.18em] text-text-3 uppercase hover:text-text-1"
      >
        <Icon name="back" className="h-4 w-4" />
        {t("documents.title")}
      </Link>
      <DocumentHead
        document={document}
        heading={(title) => <h1 className="text-2xl leading-tight font-bold break-words lg:text-[28px]">{title}</h1>}
      />
      <ErrorBox message={error ?? loadError} />
      {message && <InfoBox>{message}</InfoBox>}
      {document.trash && (
        <TrashBanner trash={document.trash} busy={trashBusy} onRestore={() => void changeTrash("restore", document.version)} />
      )}

      <div className="flex flex-wrap items-center gap-2">
        {document.originalAvailable ? (
          <>
            <a
              className="btn-secondary"
              href={`/api/documents/${document.id}/original?inline=true`}
              {...viewerTarget}
              data-testid="open-original"
            >
              <Icon name="file" className="h-4 w-4" />
              {t("documents.original")}
            </a>
            <a className="btn-ghost" href={`/api/documents/${document.id}/original`} data-testid="download-original">
              {t("documents.download")}
            </a>
          </>
        ) : (
          <p className={hint}>{t("documents.originalUnavailable")}</p>
        )}
        {(searchable === "photo" || searchable === "scan") && (
          <a
            className="btn-ghost"
            href={`/api/documents/${document.id}/archive-pdf?inline=true`}
            {...viewerTarget}
            data-testid="open-archive-pdf"
          >
            {t("documents.archivePdf")}
          </a>
        )}
      </div>

      {desktop ? <DocumentViewer document={document} /> : <DocumentPreview document={document} />}

      <ContextPanelOrFlow>
        <div className="flex flex-col gap-5">
          <Block id="document-processing" title={t("documents.processing")} testId="processing">
            <ProcessingSteps completed={progress.completed} current={progress.current} />
            {/* The processing state is shown once, here; an archived document names its archival date. */}
            <p className={`text-sm ${p.state === "FAILED" ? "text-danger" : "text-text-2"}`} data-testid="processing-state">
              {p.state === "ARCHIVED" && p.archivedAt
                ? t("documents.archivedAt", { date: formatDateTime(p.archivedAt, i18n.language) })
                : t(`documents.state.${p.state}`)}
            </p>
            {p.retryScheduled && (
              <p className={hint}>
                {t("documents.retrying")} ·{" "}
                {p.nextAttemptAt && t("documents.nextAttempt", { date: formatDateTime(p.nextAttemptAt, i18n.language) })}
              </p>
            )}
            {p.attempts > 0 && <p className={hint}>{t("documents.attempts", { count: p.attempts })}</p>}
            {p.lastErrorCode && (
              <p className="text-sm text-danger">
                {t("documents.lastError", {
                  message: t(`errors.${p.lastErrorCode}`, { defaultValue: p.lastErrorMessage ?? p.lastErrorCode }),
                })}
              </p>
            )}
            <TextRecognitionHint document={document} />
            {(searchable === "photo" || searchable === "photoPending") && (
              <p className={hint} data-testid="photo-hint">
                {searchable === "photo" ? t("documents.archivePdfHint") : t("documents.archivePdfPending")}
                {document.contentType === "image/heic" && ` ${t("documents.heicHint")}`}
              </p>
            )}
            {searchable === "scan" && (
              <p className={hint} data-testid="scan-hint">
                {t("documents.archivePdfScanHint")}
              </p>
            )}
            {p.state === "FAILED" && !trashed && (
              <div>
                <button type="button" className="btn-secondary" onClick={retry} data-testid="retry-archival">
                  {t("documents.retry")}
                </button>
              </div>
            )}
          </Block>

          <Block
            id="document-pages"
            title={t("pageEdit.derivations.section")}
            testId="document-pages"
            action={
              !trashed && (
                <Link
                  to={`/documents/pages?add=${encodeURIComponent(document.id)}`}
                  className="btn-ghost -mr-3 font-sans normal-case tracking-normal"
                  data-testid="edit-pages"
                >
                  {t("pageEdit.edit")}
                </Link>
              )
            }
          >
            {!trashed && <p className={hint}>{t("pageEdit.editHint")}</p>}
            <Derivations documentId={document.id} />
          </Block>

          {p.state !== "FAILED" && !trashed && (
            <AnalysisPanel document={document} onChange={setDocument} onError={setError} onMessage={setMessage} reload={load} />
          )}

          <Block id="document-folder-heading" title={t("documents.folder")}>
            <p className="font-mono text-sm break-words text-text-2" data-testid="document-folder">
              {document.classification.path.map((step) => step.name).join(" / ") || t("documents.unsorted")}
            </p>
            {!trashed && (
              <div className="flex flex-wrap items-end gap-2">
                <div className="min-w-0 flex-1">
                  <FolderSelect
                    categories={categories}
                    value={folderDraft}
                    onChange={setFolderDraft}
                    label={t("documents.chooseFolder")}
                    id="document-folder-select"
                  />
                </div>
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
          </Block>

          <Block
            id="document-labels"
            title={t("documents.labels")}
            action={
              !editingLabels && !trashed && (
                <button type="button" className="btn-ghost -mr-3 font-sans normal-case tracking-normal" onClick={() => setEditingLabels(true)} data-testid="edit-labels">
                  {t("documents.editLabels")}
                </button>
              )
            }
          >
            <LabelChips
              chips={document.classification.labels}
              folderName={folderName}
              onAccept={trashed ? undefined : (chip) => void review(chip, "accept")}
              onReject={trashed ? undefined : (chip) => void review(chip, "reject")}
            />
            {document.classification.labels.length === 0 && <p className={hint}>{t("documents.noLabels")}</p>}
            <p className="text-xs text-text-3">{t("documents.inheritedFromFolder")}</p>
            {editingLabels && (
              <LabelEditor
                key={document.classification.version}
                chips={document.classification.labels}
                labels={labels}
                onSave={saveLabels}
                onCreate={createLabel}
              />
            )}
          </Block>

          <Block
            id="document-subjects"
            title={t("documents.subjects")}
            action={
              !editingSubjects && !trashed && (
                <button
                  type="button"
                  className="btn-ghost -mr-3 font-sans normal-case tracking-normal"
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
              )
            }
          >
            <SubjectList subjects={document.subjects} />
            {document.classification.folderSubjects.length > 0 && (
              <div className="flex flex-wrap items-center gap-2 text-xs text-text-3">
                <span>{t("documents.fromFolderShort")}</span>
                <SubjectList subjects={document.classification.folderSubjects} />
              </div>
            )}
            {editingSubjects && (
              <div className="flex flex-wrap items-center gap-x-5 gap-y-2">
                {subjectChoices(persons, document.subjects.map(subjectKey)).map((value) => (
                  <label key={value} className="flex min-h-11 cursor-pointer items-center gap-3 text-sm">
                    <input
                      type="checkbox"
                      className="ui-checkbox"
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
                <div className="flex w-full gap-2">
                  <button type="button" className="btn-secondary" onClick={saveSubjects}>
                    {t("app.save")}
                  </button>
                  <button type="button" className="btn-ghost" onClick={() => setEditingSubjects(false)}>
                    {t("app.cancel")}
                  </button>
                </div>
              </div>
            )}
          </Block>

          <SimilarDocuments documentId={document.id} refreshKey={`${document.version}:${document.textRecognition.state}:${trashed}`} />

          {contracts.length > 0 && (
            <Block id="linked-contracts" title={t("documents.linkedContracts")}>
              <ul className="flex flex-col gap-2">
                {contracts.map((contract) => (
                  <li key={contract.id} className="card flex flex-wrap items-center justify-between gap-2">
                    <Link to={`/contracts/${contract.id}`} className="ui-stretch font-medium text-data hover:underline">
                      {contract.name}
                    </Link>
                    <span className="font-mono text-sm text-text-2">
                      {contract.deadline.determined && contract.deadline.deadline
                        ? t("contracts.deadline.cancelBy", { date: formatDate(contract.deadline.deadline, i18n.language) })
                        : t("contracts.deadline.none")}
                    </span>
                  </li>
                ))}
              </ul>
            </Block>
          )}

          <Block
            id="linked-tasks"
            title={
              <>
                {t("documents.linkedTasks")} <span className="text-text-3">{tasks.length}</span>
              </>
            }
          >
            {showTaskForm && (
              <TaskForm
                sourceDocumentId={document.id}
                onCreated={(task) => {
                  setTasks((current) => [...current, task]);
                  setShowTaskForm(false);
                  setMessage(t("tasks.created"));
                }}
              />
            )}
            {tasks.length === 0 ? (
              <EmptyState>{t("documents.noLinkedTasks")}</EmptyState>
            ) : (
              <ul className="flex flex-col gap-2">
                {tasks.map((task) => (
                  <TaskCard
                    key={task.id}
                    task={task}
                    onChange={(updated) =>
                      setTasks((current) => current.map((entry) => (entry.id === updated.id ? updated : entry)))
                    }
                  />
                ))}
              </ul>
            )}
            {!trashed && (
              <div>
                <button
                  type="button"
                  className="btn-secondary"
                  aria-expanded={showTaskForm}
                  onClick={() => setShowTaskForm((value) => !value)}
                  data-testid="new-task-toggle"
                >
                  {t("documents.newTask")}
                </button>
              </div>
            )}
          </Block>

          <Block id="document-title" title={t("documents.titleSection")}>
            {editingTitle ? (
              <div className="flex flex-wrap items-end gap-2">
                <label className="block min-w-0 flex-1" htmlFor="title-draft">
                  <span className="label">{t("documents.renameTitle")}</span>
                  <input
                    id="title-draft"
                    className="field"
                    value={titleDraft}
                    onChange={(event) => setTitleDraft(event.target.value)}
                  />
                </label>
                <button type="button" className="btn-secondary" onClick={saveTitle} data-testid="save-title">
                  {t("app.save")}
                </button>
                <button type="button" className="btn-ghost" onClick={() => setEditingTitle(false)}>
                  {t("app.cancel")}
                </button>
              </div>
            ) : (
              !trashed && (
                <div>
                  <button
                    type="button"
                    className="btn-ghost -ml-4"
                    onClick={() => {
                      setTitleDraft(document.title);
                      setEditingTitle(true);
                    }}
                    data-testid="rename-document"
                  >
                    {t("documents.renameTitle")}
                  </button>
                </div>
              )
            )}
            <details className="text-sm" data-testid="technical-details">
              <summary className="flex min-h-11 cursor-pointer items-center font-mono text-xs tracking-[0.18em] text-text-3 uppercase">
                {t("documents.technicalDetails")}
              </summary>
              <dl className="grid grid-cols-[auto_minmax(0,1fr)] gap-x-3.5 gap-y-1.5 pt-1">
                <dt className="text-text-3">{t("documents.fileName")}</dt>
                <dd className="font-mono text-xs break-all text-text-2">{document.originalFilename}</dd>
                <dt className="text-text-3">{t("documents.fileSize")}</dt>
                <dd className="font-mono text-xs text-text-2">{formatBytes(document.sizeBytes, t, i18n.language)}</dd>
                {p.paperlessDocumentId != null && (
                  <>
                    <dt className="text-text-3">{t("documents.paperlessNumber")}</dt>
                    <dd className="font-mono text-xs text-text-2">{p.paperlessDocumentId}</dd>
                  </>
                )}
                <dt className="text-text-3">{t("documents.checksum")}</dt>
                <dd className="font-mono text-xs break-all text-text-2" data-testid="checksum">
                  {document.checksumSha256}
                </dd>
              </dl>
            </details>
            <ChangeHistory
              refreshKey={`${document.version}:${document.classification.version}:${document.analysis.generation}:${document.analysis.state}:${document.title}:${document.filename.override ?? ""}`}
              createdAt={document.uploadedAt}
              load={loadHistory}
              undo={undoChange}
              formatValue={historyValue}
              labelName={historyLabel}
              readOnly={trashed}
            />
          </Block>

          {!trashed && (
            <Block id="document-trash" title={t("trash.section")}>
              <p className={hint}>{t("trash.documentHint")}</p>
              <div>
                <MoveToTrashButton busy={trashBusy} onTrash={() => void changeTrash("trash", document.version)} testId="trash-document" />
              </div>
            </Block>
          )}
        </div>
      </ContextPanelOrFlow>
      {trashToast}
    </div>
  );
}
