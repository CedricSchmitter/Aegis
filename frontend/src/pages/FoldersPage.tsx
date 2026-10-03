import { useCallback, useEffect, useMemo, useState } from "react";
import { useTranslation } from "react-i18next";
import { api, unwrap } from "../api/client";
import { FACETS, type CategoryDto, type CategoryListResponse, type Facet, type LabelDto } from "../api/types";
import { useSession } from "../auth/SessionContext";
import { FolderSelect } from "../components/FolderSelect";
import { LabelPicker } from "../components/LabelPicker";
import { BackLink, BigTitle, EmptyState, ErrorBox, InfoBox, Loading } from "../components/common";
import { Icon } from "../components/shell/icons";
import { Chip, Dialog } from "../components/ui";
import { byId, labelName, subjectChoices, subjectKey } from "../lib/labels";
import { problemMessage } from "../lib/problem";
import { buildTree, flatten, subtreeCount } from "../lib/tree";

/**
 * Managing the shared folders: create, rename, move, the labels and subjects a folder passes on, and
 * deletion, which moves documents and subfolders one level up and never deletes a document.
 */
export function FoldersPage() {
  const { t } = useTranslation();
  const { members, persons } = useSession();
  const [catalog, setCatalog] = useState<CategoryListResponse | null>(null);
  const [labels, setLabels] = useState<LabelDto[]>([]);
  const [error, setError] = useState<string | null>(null);
  const [message, setMessage] = useState<string | null>(null);
  const [newName, setNewName] = useState("");
  const [newParent, setNewParent] = useState<string | null>(null);
  const [open, setOpen] = useState<string | null>(null);
  const [nameDraft, setNameDraft] = useState("");
  const [parentDraft, setParentDraft] = useState<string | null>(null);
  const [labelDraft, setLabelDraft] = useState<string[]>([]);
  const [subjectDraft, setSubjectDraft] = useState<string[]>([]);
  const [deleting, setDeleting] = useState<CategoryDto | null>(null);

  const load = useCallback(async () => {
    try {
      const [tree, vocabulary] = await Promise.all([api.GET("/api/categories"), api.GET("/api/labels")]);
      setCatalog(unwrap(tree));
      setLabels(unwrap(vocabulary));
      setError(null);
    } catch (failure) {
      setError(problemMessage(t, failure));
    }
  }, [t]);

  useEffect(() => {
    void load();
  }, [load]);

  const categories = useMemo(() => catalog?.categories ?? [], [catalog]);
  const nodes = useMemo(() => buildTree(categories), [categories]);
  const known = byId(labels);

  const startEditing = (category: CategoryDto) => {
    setOpen(category.id);
    setNameDraft(category.name);
    setParentDraft(category.parentId ?? null);
    setLabelDraft([...category.impliedLabelIds]);
    setSubjectDraft(
      category.impliedSubjects.map((subject) =>
        subject.kind === "MEMBER" ? `MEMBER:${subject.memberId}` : subject.kind,
      ),
    );
  };

  const run = async (action: () => Promise<void>) => {
    try {
      await action();
      setError(null);
      await load();
    } catch (failure) {
      setError(problemMessage(t, failure));
      await load();
    }
  };

  const create = () =>
    run(async () => {
      const created = unwrap(
        await api.POST("/api/categories", { body: { name: newName, parentId: newParent } }),
      );
      setMessage(t("folders.created", { name: created.name }));
      setNewName("");
    });

  const rename = (category: CategoryDto) =>
    run(async () => {
      await api.PUT("/api/categories/{id}/name", {
        params: { path: { id: category.id } },
        body: { name: nameDraft, expectedVersion: category.version },
      }).then(unwrap);
      setMessage(t("folders.renamed"));
    });

  const move = (category: CategoryDto) =>
    run(async () => {
      await api.PUT("/api/categories/{id}/parent", {
        params: { path: { id: category.id } },
        body: { parentId: parentDraft, expectedVersion: category.version },
      }).then(unwrap);
      setMessage(t("folders.moved"));
    });

  const saveImplications = (category: CategoryDto) =>
    run(async () => {
      const subjects = subjectDraft.map((value) => {
        const [kind, memberId] = value.split(":");
        return { kind, memberId: memberId ?? null };
      });
      await api.PUT("/api/categories/{id}/implications", {
        params: { path: { id: category.id } },
        body: { labelIds: labelDraft, subjects, expectedVersion: category.version },
      }).then(unwrap);
      setMessage(t("folders.impliedSaved"));
    });

  const remove = (category: CategoryDto) =>
    run(async () => {
      const result = unwrap(
        await api.DELETE("/api/categories/{id}", {
          params: { path: { id: category.id }, query: { expectedVersion: category.version } },
        }),
      );
      setOpen(null);
      setMessage(
        t("folders.deleted", {
          name: category.name,
          documents: result.documentsMoved,
          folders: result.foldersMoved,
          labels: result.labelsKept,
        }),
      );
    });

  if (!catalog) return error ? <ErrorBox message={error} /> : <Loading />;

  const subjectName = (subject: CategoryDto["impliedSubjects"][number]) =>
    subject.kind === "MEMBER"
      ? t("subjects.MEMBER", {
          name: members.find((member) => member.id === subject.memberId)?.displayName ?? t("app.unknownMember"),
        })
      : t(`subjects.${subject.kind}`);

  return (
    <div className="flex max-w-3xl flex-col gap-4">
      <BackLink to="/documents">{t("documents.title")}</BackLink>
      <BigTitle count={t("folders.count", { count: categories.length })}>{t("folders.title")}</BigTitle>
      <ErrorBox message={error} />
      {message && <InfoBox>{message}</InfoBox>}

      <section className="card flex flex-col gap-3" aria-label={t("folders.create")}>
        <div>
          <label className="label" htmlFor="new-folder-name">
            {t("folders.newName")}
          </label>
          <input
            id="new-folder-name"
            className="field"
            value={newName}
            data-testid="new-folder-name"
            onChange={(event) => setNewName(event.target.value)}
          />
        </div>
        <FolderSelect
          categories={categories}
          value={newParent}
          onChange={setNewParent}
          label={t("folders.createIn")}
          topLabel={t("folders.top")}
          id="new-folder-parent"
        />
        <div>
          <button type="button" className="btn-primary" disabled={!newName.trim()} onClick={create} data-testid="create-folder">
            <Icon name="plus" className="h-[18px] w-[18px]" />
            {t("folders.create")}
          </button>
        </div>
      </section>

      {categories.length === 0 ? (
        <EmptyState>{t("folders.empty")}</EmptyState>
      ) : (
        <ul className="border-t border-line" aria-label={t("folders.tree")}>
          {flatten(nodes).map((node) => {
            const category = node.category;
            const editing = open === category.id;
            return (
              <li
                key={category.id}
                className={`border-b border-line py-3 ${editing ? "bg-surface px-3" : ""}`}
                data-testid="folder-row"
                data-folder-id={category.id}
                style={editing ? undefined : { paddingLeft: `${node.depth * 1.25}rem` }}
              >
                <div className="flex items-start justify-between gap-3">
                  <div className="flex min-w-0 gap-2.5">
                    <Icon name="folder" className="mt-0.5 h-[18px] w-[18px] flex-none text-data" />
                    <div className="flex min-w-0 flex-col gap-1">
                      <p className="text-[15px] leading-snug font-semibold break-words">{category.name}</p>
                      <p className="ui-meta">
                        <span>{t("folders.documents", { count: category.documentCount })}</span>
                        <span>{t("folders.subtree", { count: subtreeCount(node) })}</span>
                      </p>
                      {(category.impliedLabelIds.length > 0 || category.impliedSubjects.length > 0) && (
                        <ul className="flex flex-wrap gap-1" aria-label={t("folders.implications")}>
                          {category.impliedLabelIds.map((labelId) => (
                            <li key={labelId}>
                              <Chip>{labelName(known.get(labelId), t) || labelId}</Chip>
                            </li>
                          ))}
                          {category.impliedSubjects.map((subject) => (
                            <li key={subject.kind + subject.memberId}>
                              <Chip facet={t("folders.impliedSubjects")}>{subjectName(subject)}</Chip>
                            </li>
                          ))}
                        </ul>
                      )}
                    </div>
                  </div>
                  <button
                    type="button"
                    className="btn-ghost flex-none"
                    aria-expanded={editing}
                    data-testid={`edit-folder-${category.id}`}
                    onClick={() => (editing ? setOpen(null) : startEditing(category))}
                  >
                    {editing ? t("app.close") : t("app.edit")}
                  </button>
                </div>
                {editing && (
                  <div className="mt-3 flex flex-col gap-4 border-t border-line pt-3">
                    <div className="flex flex-wrap items-end gap-2">
                      <label className="block min-w-0 flex-1">
                        <span className="label">{t("folders.rename")}</span>
                        <input
                          className="field"
                          value={nameDraft}
                          data-testid="folder-name-input"
                          onChange={(event) => setNameDraft(event.target.value)}
                        />
                      </label>
                      <button type="button" className="btn-secondary" onClick={() => rename(category)} data-testid="save-folder-name">
                        {t("folders.rename")}
                      </button>
                    </div>
                    <div className="flex flex-wrap items-end gap-2">
                      <div className="min-w-0 flex-1">
                        <FolderSelect
                          categories={categories}
                          value={parentDraft}
                          onChange={setParentDraft}
                          exclude={category.id}
                          label={t("folders.moveTo")}
                          topLabel={t("folders.top")}
                          id={`parent-${category.id}`}
                        />
                      </div>
                      <button type="button" className="btn-secondary" onClick={() => move(category)} data-testid="save-folder-parent">
                        {t("folders.move")}
                      </button>
                    </div>

                    <div className="flex flex-col gap-2">
                      <p className="ui-sec">{t("folders.implications")}</p>
                      <p className="text-[13px] leading-relaxed text-text-3">{t("folders.impliedHint")}</p>
                      <p className="font-mono text-xs text-text-3">{t("folders.affects", { count: subtreeCount(node) })}</p>
                      {labelDraft.length > 0 && (
                        <ul className="flex flex-wrap gap-1">
                          {labelDraft.map((labelId) => (
                            <li key={labelId}>
                              <Chip onRemove={() => setLabelDraft((draft) => draft.filter((entry) => entry !== labelId))}>
                                {labelName(known.get(labelId), t) || labelId}
                              </Chip>
                            </li>
                          ))}
                        </ul>
                      )}
                      {FACETS.map((facet: Facet) => (
                        <div key={facet} className="flex flex-col gap-1">
                          <p className="text-xs text-text-3">{t(`facets.${facet}`)}</p>
                          <LabelPicker
                            facet={facet}
                            labels={labels}
                            onPick={(label) =>
                              setLabelDraft((draft) => {
                                const withoutType =
                                  facet === "DOCUMENT_TYPE" ? draft.filter((entry) => known.get(entry)?.facet !== "DOCUMENT_TYPE") : draft;
                                return withoutType.includes(label.id) ? withoutType : [...withoutType, label.id];
                              })
                            }
                          />
                        </div>
                      ))}
                      <fieldset className="flex flex-col gap-1">
                        <legend className="mb-1 text-xs text-text-3">{t("folders.impliedSubjects")}</legend>
                        <div className="flex flex-wrap gap-x-5">
                          {subjectChoices(persons, category.impliedSubjects.map(subjectKey)).map((value) => (
                            <label key={value} className="flex min-h-11 cursor-pointer items-center gap-2.5 text-sm">
                              <input
                                type="checkbox"
                                className="ui-checkbox"
                                data-testid={`implied-subject-${value}`}
                                checked={subjectDraft.includes(value)}
                                onChange={() =>
                                  setSubjectDraft((draft) =>
                                    draft.includes(value) ? draft.filter((entry) => entry !== value) : [...draft, value],
                                  )
                                }
                              />
                              {value.startsWith("MEMBER:")
                                ? (members.find((member) => `MEMBER:${member.id}` === value)?.displayName ?? value)
                                : t(`subjects.${value}`)}
                            </label>
                          ))}
                        </div>
                      </fieldset>
                      <div>
                        <button type="button" className="btn-secondary" onClick={() => saveImplications(category)} data-testid="save-implications">
                          {t("app.save")}
                        </button>
                      </div>
                    </div>

                    <div className="flex flex-col gap-1 border-t border-line pt-3">
                      <p className="text-[13px] leading-relaxed text-text-3">{t("folders.deleteHint")}</p>
                      <button
                        type="button"
                        className="btn-ghost -ml-4 self-start text-danger"
                        data-testid={`delete-folder-${category.id}`}
                        onClick={() => setDeleting(category)}
                      >
                        {t("folders.delete")}
                      </button>
                    </div>
                  </div>
                )}
              </li>
            );
          })}
        </ul>
      )}

      <Dialog
        open={deleting !== null}
        onClose={() => setDeleting(null)}
        title={t("folders.deleteTitle")}
        actions={
          <>
            <button type="button" className="btn-ghost" onClick={() => setDeleting(null)}>
              {t("app.cancel")}
            </button>
            <button
              type="button"
              className="btn-danger"
              data-testid="delete-folder-confirm"
              onClick={() => {
                const category = deleting;
                setDeleting(null);
                if (category) void remove(category);
              }}
            >
              {t("folders.delete")}
            </button>
          </>
        }
      >
        <p className="text-sm leading-relaxed text-text-2">{deleting && t("folders.deleteConfirm", { name: deleting.name })}</p>
      </Dialog>
    </div>
  );
}
