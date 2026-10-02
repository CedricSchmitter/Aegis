import { useCallback, useEffect, useState } from "react";
import { useTranslation } from "react-i18next";
import { api, unwrap } from "../api/client";
import { FACETS, type Facet, type LabelDto } from "../api/types";
import { BackLink, BigTitle, EmptyState, ErrorBox, InfoBox, Loading } from "../components/common";
import { Badge } from "../components/ui";
import { activeLabels, labelName } from "../lib/labels";
import { problemMessage } from "../lib/problem";

/**
 * Managing the household's labels by group: create, rename, other spellings, merge, change the group,
 * retire and delete an unused one. No action deletes a document or loses an assignment.
 */
export function LabelsPage() {
  const { t } = useTranslation();
  const [labels, setLabels] = useState<LabelDto[] | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [message, setMessage] = useState<string | null>(null);
  const [newNames, setNewNames] = useState<Record<string, string>>({});
  const [open, setOpen] = useState<string | null>(null);
  const [nameDraft, setNameDraft] = useState("");
  const [aliasDraft, setAliasDraft] = useState("");
  const [mergeTarget, setMergeTarget] = useState("");
  const [facetTarget, setFacetTarget] = useState<Facet>("TOPIC");

  const load = useCallback(async () => {
    try {
      setLabels(unwrap(await api.GET("/api/labels")));
      setError(null);
    } catch (failure) {
      setError(problemMessage(t, failure));
    }
  }, [t]);

  useEffect(() => {
    void load();
  }, [load]);

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

  const create = (facet: Facet) =>
    run(async () => {
      const name = (newNames[facet] ?? "").trim();
      const created = unwrap(await api.POST("/api/labels", { body: { facet, name } }));
      setMessage(t("labels.created", { name: labelName(created, t) }));
      setNewNames((current) => ({ ...current, [facet]: "" }));
    });

  const startEditing = (label: LabelDto) => {
    setOpen(label.id);
    setNameDraft(labelName(label, t));
    setAliasDraft(label.aliases.join("\n"));
    setMergeTarget("");
    setFacetTarget(label.facet === "TAG" ? "TOPIC" : (label.facet as Facet));
  };

  const rename = (label: LabelDto) =>
    run(async () => {
      await api.PUT("/api/labels/{id}/name", {
        params: { path: { id: label.id } },
        body: { name: nameDraft, expectedVersion: label.version },
      }).then(unwrap);
      setMessage(t("labels.renamed", { name: nameDraft }));
    });

  const saveAliases = (label: LabelDto) =>
    run(async () => {
      const aliases = aliasDraft
        .split("\n")
        .map((alias) => alias.trim())
        .filter((alias) => alias.length > 0);
      await api.PUT("/api/labels/{id}/aliases", {
        params: { path: { id: label.id } },
        body: { aliases, expectedVersion: label.version },
      }).then(unwrap);
      setMessage(t("labels.aliasesSaved"));
    });

  const setStatus = (label: LabelDto, action: "retire" | "reactivate") =>
    run(async () => {
      const path = action === "retire" ? "/api/labels/{id}/retire" : "/api/labels/{id}/reactivate";
      await api.POST(path, {
        params: { path: { id: label.id } },
        body: { expectedVersion: label.version },
      }).then(unwrap);
    });

  const merge = (label: LabelDto) =>
    run(async () => {
      const result = unwrap(
        await api.POST("/api/labels/{id}/merge", {
          params: { path: { id: label.id } },
          body: { targetId: mergeTarget, expectedVersion: label.version },
        }),
      );
      setOpen(null);
      setMessage(
        t("labels.merged", {
          source: labelName(label, t),
          target: labelName(result.target, t),
          documents: result.documentsUpdated,
        }),
      );
    });

  const convert = (label: LabelDto) =>
    run(async () => {
      await api.POST("/api/labels/{id}/facet", {
        params: { path: { id: label.id } },
        body: { facet: facetTarget, expectedVersion: label.version },
      }).then(unwrap);
    });

  const remove = (label: LabelDto) =>
    run(async () => {
      unwrap(
        await api.DELETE("/api/labels/{id}", {
          params: { path: { id: label.id }, query: { expectedVersion: label.version } },
        }),
      );
      setOpen(null);
      setMessage(t("labels.deleted"));
    });

  if (!labels) return error ? <ErrorBox message={error} /> : <Loading />;

  return (
    <div className="flex max-w-3xl flex-col gap-4">
      <BackLink to="/documents">{t("documents.title")}</BackLink>
      <BigTitle count={t("labels.count", { count: labels.filter((label) => label.status !== "MERGED").length })}>{t("labels.title")}</BigTitle>
      <p className="-mt-2 text-sm leading-relaxed text-text-3">{t("labels.intro")}</p>
      <ErrorBox message={error} />
      {message && <InfoBox>{message}</InfoBox>}

      {FACETS.map((facet) => {
        const group = labels.filter((label) => label.facet === facet);
        return (
          <section key={facet} className="flex flex-col gap-2 pt-2" aria-labelledby={`facet-${facet}`}>
            <h2 id={`facet-${facet}`} className="ui-sec">
              <span>{t(`facets.${facet}`)}</span>
              <span>{group.length}</span>
            </h2>
            <p className="text-[13px] leading-relaxed text-text-3">{t(`facets.hint${facet}`)}</p>
            <div className="flex flex-wrap items-end gap-2">
              <label className="block min-w-0 flex-1">
                <span className="label">{t("labels.newName")}</span>
                <input
                  className="field"
                  value={newNames[facet] ?? ""}
                  data-testid={`new-label-${facet}`}
                  onChange={(event) => setNewNames((current) => ({ ...current, [facet]: event.target.value }))}
                />
              </label>
              <button
                type="button"
                className="btn-secondary"
                disabled={!(newNames[facet] ?? "").trim()}
                onClick={() => create(facet)}
                data-testid={`create-label-${facet}`}
              >
                {t("labels.create")}
              </button>
            </div>
            {group.length === 0 ? (
              <EmptyState>{t("labels.empty")}</EmptyState>
            ) : (
              <ul className="border-t border-line">
                {group.map((label) => {
                  const editing = open === label.id;
                  const mergeCandidates = activeLabels(labels, facet).filter((entry) => entry.id !== label.id);
                  return (
                    <li
                      key={label.id}
                      className={`border-b border-line py-3 ${editing ? "bg-surface px-3" : ""}`}
                      data-testid="label-row"
                      data-label-id={label.id}
                    >
                      <div className="flex items-start justify-between gap-3">
                        <div className="flex min-w-0 flex-col gap-1">
                          <p className="flex flex-wrap items-center gap-2">
                            <span className={`text-[15px] leading-snug font-semibold break-words ${label.status === "ACTIVE" ? "" : "text-text-3"}`}>
                              {labelName(label, t)}
                            </span>
                            {label.builtinKey && <Badge tone="neutral">{t("labels.builtinBadge")}</Badge>}
                            {label.status !== "ACTIVE" && (
                              <Badge tone="neutral" data-state={`LABEL_${label.status}`}>
                                {t(`labels.status.${label.status}`)}
                              </Badge>
                            )}
                          </p>
                          <p className="ui-meta">
                            <span>{t("labels.assignments", { count: label.assignmentCount })}</span>
                            <span>{t("labels.folderUses", { count: label.folderCount })}</span>
                            {label.suggestionCount > 0 && (
                              <span className="text-action">{t("labels.suggestionsPending", { count: label.suggestionCount })}</span>
                            )}
                          </p>
                          {label.aliases.length > 0 && <p className="text-[13px] break-words text-text-3">{label.aliases.join(", ")}</p>}
                          {label.status === "MERGED" && label.mergedInto && (
                            <p className="text-[13px] text-text-3">
                              {t("labels.mergedInto", {
                                name: labelName(
                                  labels.find((entry) => entry.id === label.mergedInto),
                                  t,
                                ),
                              })}
                            </p>
                          )}
                        </div>
                        {label.status !== "MERGED" && (
                          <button
                            type="button"
                            className="btn-ghost flex-none"
                            aria-expanded={editing}
                            data-testid={`edit-label-${label.id}`}
                            onClick={() => (editing ? setOpen(null) : startEditing(label))}
                          >
                            {editing ? t("app.close") : t("app.edit")}
                          </button>
                        )}
                      </div>
                      {editing && (
                        <div className="mt-3 flex flex-col gap-4 border-t border-line pt-3">
                          <div className="flex flex-wrap items-end gap-2">
                            <label className="block min-w-0 flex-1">
                              <span className="label">{t("labels.name")}</span>
                              <input className="field" value={nameDraft} data-testid="label-name-input" onChange={(event) => setNameDraft(event.target.value)} />
                            </label>
                            <button type="button" className="btn-secondary" onClick={() => rename(label)} data-testid="save-label-name">
                              {t("labels.save")}
                            </button>
                          </div>
                          <div className="flex flex-col gap-1">
                            <label className="block">
                              <span className="label">{t("labels.aliases")}</span>
                              <textarea
                                className="field min-h-24"
                                value={aliasDraft}
                                data-testid="label-aliases-input"
                                onChange={(event) => setAliasDraft(event.target.value)}
                              />
                            </label>
                            <p className="text-[13px] leading-relaxed text-text-3">{t("labels.aliasesHint")}</p>
                            <div>
                              <button type="button" className="btn-secondary" onClick={() => saveAliases(label)} data-testid="save-label-aliases">
                                {t("labels.save")}
                              </button>
                            </div>
                          </div>

                          {mergeCandidates.length > 0 && (
                            <div className="flex flex-col gap-1">
                              <label className="block">
                                <span className="label">{t("labels.mergeInto")}</span>
                                <select className="field" value={mergeTarget} data-testid="merge-target" onChange={(event) => setMergeTarget(event.target.value)}>
                                  <option value="">—</option>
                                  {mergeCandidates.map((candidate) => (
                                    <option key={candidate.id} value={candidate.id}>
                                      {labelName(candidate, t)}
                                    </option>
                                  ))}
                                </select>
                              </label>
                              <p className="text-[13px] leading-relaxed text-text-3">{t("labels.mergeHint")}</p>
                              <div>
                                <button type="button" className="btn-secondary" disabled={!mergeTarget} onClick={() => merge(label)} data-testid="merge-label">
                                  {t("labels.merge")}
                                </button>
                              </div>
                            </div>
                          )}

                          {facet === "TAG" && (
                            <div className="flex flex-col gap-1">
                              <div className="flex flex-wrap items-end gap-2">
                                <label className="block min-w-0 flex-1">
                                  <span className="label">{t("labels.convert")}</span>
                                  <select className="field" value={facetTarget} onChange={(event) => setFacetTarget(event.target.value as Facet)}>
                                    <option value="ORGANIZATION">{t("facets.ORGANIZATION")}</option>
                                    <option value="TOPIC">{t("facets.TOPIC")}</option>
                                  </select>
                                </label>
                                <button type="button" className="btn-secondary" onClick={() => convert(label)} data-testid="convert-label">
                                  {t("labels.convert")}
                                </button>
                              </div>
                              <p className="text-[13px] leading-relaxed text-text-3">{t("labels.convertHint")}</p>
                            </div>
                          )}

                          <div className="flex flex-col gap-1 border-t border-line pt-3">
                            <p className="text-[13px] leading-relaxed text-text-3">{t("labels.retireHint")}</p>
                            <div className="flex flex-wrap items-center gap-2">
                              {label.status === "ACTIVE" ? (
                                <button type="button" className="btn-ghost -ml-4" onClick={() => setStatus(label, "retire")} data-testid="retire-label">
                                  {t("labels.retire")}
                                </button>
                              ) : (
                                <button type="button" className="btn-ghost -ml-4" onClick={() => setStatus(label, "reactivate")} data-testid="reactivate-label">
                                  {t("labels.reactivate")}
                                </button>
                              )}
                              {!label.builtinKey && label.assignmentCount === 0 && label.folderCount === 0 && (
                                <button type="button" className="btn-ghost text-danger" onClick={() => remove(label)} data-testid="delete-label">
                                  {t("labels.delete")}
                                </button>
                              )}
                            </div>
                          </div>
                        </div>
                      )}
                    </li>
                  );
                })}
              </ul>
            )}
          </section>
        );
      })}
    </div>
  );
}
