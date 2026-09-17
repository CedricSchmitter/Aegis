import { useCallback, useEffect, useMemo, useState } from "react";
import { Link } from "react-router";
import { useTranslation } from "react-i18next";
import { api, unwrap } from "../api/client";
import { FACETS, type CategoryDto, type CategoryListResponse, type Facet, type LabelDto } from "../api/types";
import { useSession } from "../auth/SessionContext";
import { FolderSelect } from "../components/FolderSelect";
import { LabelPicker } from "../components/LabelPicker";
import { EmptyState, ErrorBox, InfoBox, Loading, PageTitle } from "../components/common";
import { byId, labelName } from "../lib/labels";
import { problemMessage } from "../lib/problem";
import { buildTree, flatten, subtreeCount } from "../lib/tree";

/** Structure created by the starter button; ids are fixed so a second click changes nothing. */
const STARTER = [
  { key: "house", id: "aeb51000-0000-4000-8000-000000000001", parent: null as string | null, subject: "HOUSE" },
  { key: "invoices", id: "aeb51000-0000-4000-8000-000000000002", parent: "house", builtin: "invoice" },
  { key: "internet", id: "aeb51000-0000-4000-8000-000000000003", parent: "invoices", builtin: "internet" },
  { key: "water", id: "aeb51000-0000-4000-8000-000000000004", parent: "invoices", builtin: "water" },
  { key: "insurance", id: "aeb51000-0000-4000-8000-000000000005", parent: "house", builtin: "insurance" },
] as const;

export function FoldersPage() {
  const { t } = useTranslation();
  const { members } = useSession();
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

  const createStarter = () =>
    run(async () => {
      const ids = new Map<string, string>();
      for (const entry of STARTER) {
        const created = unwrap(
          await api.POST("/api/categories", {
            body: {
              id: entry.id,
              parentId: entry.parent ? (ids.get(entry.parent) ?? null) : null,
              name: t(`folders.structure.${entry.key}`),
            },
          }),
        );
        ids.set(entry.key, created.id);
        const builtin = "builtin" in entry ? labels.find((label) => label.builtinKey === entry.builtin) : undefined;
        const subject = "subject" in entry ? entry.subject : undefined;
        if (builtin || subject) {
          await api.PUT("/api/categories/{id}/implications", {
            params: { path: { id: created.id } },
            body: {
              labelIds: builtin ? [builtin.id] : [],
              subjects: subject ? [{ kind: subject, memberId: null }] : [],
              expectedVersion: created.version,
            },
          }).then(unwrap);
        }
      }
      setMessage(t("folders.starterDone"));
    });

  if (!catalog) return error ? <ErrorBox message={error} /> : <Loading />;

  return (
    <>
      <p className="mb-2 text-sm">
        <Link to="/documents" className="text-sky-800 hover:underline dark:text-sky-300">
          ← {t("documents.title")}
        </Link>
      </p>
      <PageTitle>{t("folders.title")}</PageTitle>
      <p className="mb-3 text-sm text-stone-600 dark:text-stone-400">{t("folders.intro")}</p>
      <ErrorBox message={error} />
      {message && <InfoBox>{message}</InfoBox>}

      <section className="card mb-4 flex flex-col gap-2" aria-label={t("folders.create")}>
        <h2 className="font-semibold">{t("folders.create")}</h2>
        <label className="label" htmlFor="new-folder-name">
          {t("folders.name")}
        </label>
        <input
          id="new-folder-name"
          className="field"
          value={newName}
          data-testid="new-folder-name"
          onChange={(event) => setNewName(event.target.value)}
        />
        <FolderSelect
          categories={categories}
          value={newParent}
          onChange={setNewParent}
          label={t("folders.moveTo")}
          topLabel={t("folders.top")}
          id="new-folder-parent"
        />
        <div className="flex flex-wrap gap-2">
          <button type="button" className="btn-primary" disabled={!newName.trim()} onClick={create}
                  data-testid="create-folder">
            {t("folders.create")}
          </button>
          <button type="button" className="btn-secondary" onClick={createStarter} data-testid="create-starter">
            {t("folders.starter")}
          </button>
        </div>
        <p className="text-xs text-stone-500">{t("folders.starterHint")}</p>
      </section>

      {categories.length === 0 ? (
        <EmptyState>{t("folders.empty")}</EmptyState>
      ) : (
        <ul className="flex flex-col gap-2" aria-label={t("folders.tree")}>
          {flatten(nodes).map((node) => {
            const category = node.category;
            const editing = open === category.id;
            return (
              <li key={category.id} className="card" data-testid="folder-row" data-folder-id={category.id}
                  style={{ marginLeft: `${node.depth * 1}rem` }}>
                <div className="flex flex-wrap items-center justify-between gap-2">
                  <div>
                    <p className="font-semibold">{category.name}</p>
                    <p className="text-xs text-stone-500">
                      {t("folders.documents", { count: category.documentCount })} ·{" "}
                      {t("folders.subtree", { count: subtreeCount(node) })}
                    </p>
                    <ul className="mt-1 flex flex-wrap gap-1">
                      {category.impliedLabelIds.map((labelId) => (
                        <li key={labelId} className="badge bg-stone-200 dark:bg-stone-800">
                          {labelName(known.get(labelId), t) || labelId}
                        </li>
                      ))}
                      {category.impliedSubjects.map((subject) => (
                        <li key={subject.kind + subject.memberId} className="badge bg-stone-200 dark:bg-stone-800">
                          {subject.kind === "MEMBER"
                            ? t("subjects.MEMBER", {
                                name:
                                  members.find((member) => member.id === subject.memberId)?.displayName ??
                                  t("app.unknownMember"),
                              })
                            : t(`subjects.${subject.kind}`)}
                        </li>
                      ))}
                    </ul>
                  </div>
                  <button
                    type="button"
                    className="btn-secondary"
                    data-testid={`edit-folder-${category.id}`}
                    onClick={() => (editing ? setOpen(null) : startEditing(category))}
                  >
                    {editing ? t("app.close") : t("app.save")}
                  </button>
                </div>
                {editing && (
                  <div className="mt-3 flex flex-col gap-3 border-t border-stone-200 pt-3 dark:border-stone-800">
                    <div className="flex flex-wrap items-end gap-2">
                      <label className="block">
                        <span className="label">{t("folders.rename")}</span>
                        <input
                          className="field"
                          value={nameDraft}
                          data-testid="folder-name-input"
                          onChange={(event) => setNameDraft(event.target.value)}
                        />
                      </label>
                      <button type="button" className="btn-secondary" onClick={() => rename(category)}
                              data-testid="save-folder-name">
                        {t("folders.rename")}
                      </button>
                    </div>
                    <div className="flex flex-wrap items-end gap-2">
                      <FolderSelect
                        categories={categories}
                        value={parentDraft}
                        onChange={setParentDraft}
                        exclude={category.id}
                        label={t("folders.moveTo")}
                        topLabel={t("folders.top")}
                        id={`parent-${category.id}`}
                      />
                      <button type="button" className="btn-secondary" onClick={() => move(category)}
                              data-testid="save-folder-parent">
                        {t("folders.move")}
                      </button>
                    </div>

                    <div className="flex flex-col gap-2">
                      <p className="label mb-0">{t("folders.implications")}</p>
                      <p className="text-xs text-stone-500">{t("folders.impliedHint")}</p>
                      <p className="text-xs text-stone-500">
                        {t("folders.affects", { count: subtreeCount(node) })}
                      </p>
                      <ul className="flex flex-wrap gap-1">
                        {labelDraft.map((labelId) => (
                          <li key={labelId}>
                            <span className="badge gap-1 bg-stone-200 dark:bg-stone-800">
                              {labelName(known.get(labelId), t) || labelId}
                              <button
                                type="button"
                                className="px-1"
                                aria-label={t("documents.removeLabel", { name: labelName(known.get(labelId), t) })}
                                onClick={() => setLabelDraft((draft) => draft.filter((entry) => entry !== labelId))}
                              >
                                ×
                              </button>
                            </span>
                          </li>
                        ))}
                      </ul>
                      {FACETS.map((facet: Facet) => (
                        <div key={facet}>
                          <p className="text-xs text-stone-500">{t(`facets.${facet}`)}</p>
                          <LabelPicker
                            facet={facet}
                            labels={labels}
                            onPick={(label) =>
                              setLabelDraft((draft) => {
                                const withoutType =
                                  facet === "DOCUMENT_TYPE"
                                    ? draft.filter((entry) => known.get(entry)?.facet !== "DOCUMENT_TYPE")
                                    : draft;
                                return withoutType.includes(label.id) ? withoutType : [...withoutType, label.id];
                              })
                            }
                          />
                        </div>
                      ))}
                      <fieldset>
                        <legend className="text-xs text-stone-500">{t("folders.impliedSubjects")}</legend>
                        <div className="flex flex-wrap gap-3">
                          {["HOUSEHOLD", "HOUSE", ...members.map((member) => `MEMBER:${member.id}`)].map((value) => (
                            <label key={value} className="flex items-center gap-2 text-sm">
                              <input
                                type="checkbox"
                                checked={subjectDraft.includes(value)}
                                onChange={() =>
                                  setSubjectDraft((draft) =>
                                    draft.includes(value)
                                      ? draft.filter((entry) => entry !== value)
                                      : [...draft, value],
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
                        <button type="button" className="btn-primary" onClick={() => saveImplications(category)}
                                data-testid="save-implications">
                          {t("app.save")}
                        </button>
                      </div>
                    </div>

                    <div className="border-t border-stone-200 pt-3 dark:border-stone-800">
                      <button
                        type="button"
                        className="btn-secondary"
                        data-testid={`delete-folder-${category.id}`}
                        onClick={() => {
                          if (window.confirm(t("folders.deleteConfirm", { name: category.name }))) {
                            void remove(category);
                          }
                        }}
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
    </>
  );
}
