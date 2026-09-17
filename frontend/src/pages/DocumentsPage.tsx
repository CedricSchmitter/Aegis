import { useCallback, useEffect, useMemo, useState, type FormEvent } from "react";
import { Link, useSearchParams } from "react-router";
import { useTranslation } from "react-i18next";
import { api, unwrap } from "../api/client";
import type { CategoryListResponse, DocumentListResponse, LabelDto } from "../api/types";
import { useSession } from "../auth/SessionContext";
import { Breadcrumbs } from "../components/Breadcrumbs";
import { DocumentCard } from "../components/DocumentCard";
import { FacetFilters } from "../components/FacetFilters";
import { FolderSelect } from "../components/FolderSelect";
import { FolderTree } from "../components/FolderTree";
import { EmptyState, ErrorBox, InfoBox, Loading, PageTitle } from "../components/common";
import {
  UNSORTED,
  activeFilterCount,
  parseFilters,
  toApiQuery,
  toSearchParams,
  withoutFilters,
  type CatalogFilters,
} from "../lib/catalogSearch";
import { problemMessage } from "../lib/problem";

export function DocumentsPage() {
  const { t } = useTranslation();
  const { members } = useSession();
  const [params, setParams] = useSearchParams();
  const filters = useMemo(() => parseFilters(params), [params]);
  const [list, setList] = useState<DocumentListResponse | null>(null);
  const [catalog, setCatalog] = useState<CategoryListResponse | null>(null);
  const [labels, setLabels] = useState<LabelDto[]>([]);
  const [error, setError] = useState<string | null>(null);
  const [message, setMessage] = useState<string | null>(null);
  const [selection, setSelection] = useState<string[]>([]);
  const [moveTarget, setMoveTarget] = useState<string | null>(null);
  const [searchText, setSearchText] = useState(filters.query);
  const [uploadError, setUploadError] = useState<string | null>(null);
  const [uploading, setUploading] = useState(false);
  const [file, setFile] = useState<File | null>(null);
  const [uploadSubjects, setUploadSubjects] = useState<string[]>(["HOUSEHOLD"]);
  const [uploadFolder, setUploadFolder] = useState<string | null>(null);
  const [showFolders, setShowFolders] = useState(false);

  const apply = useCallback(
    (next: CatalogFilters) => {
      setParams(toSearchParams(next));
      setSelection([]);
    },
    [setParams],
  );

  const loadCatalog = useCallback(async () => {
    try {
      const [categories, vocabulary] = await Promise.all([
        api.GET("/api/categories"),
        api.GET("/api/labels"),
      ]);
      setCatalog(unwrap(categories));
      setLabels(unwrap(vocabulary));
    } catch (failure) {
      setError(problemMessage(t, failure));
    }
  }, [t]);

  const load = useCallback(async () => {
    try {
      setList(unwrap(await api.GET("/api/documents", { params: { query: toApiQuery(filters) } })));
      setError(null);
    } catch (failure) {
      setError(problemMessage(t, failure));
    }
  }, [filters, t]);

  useEffect(() => {
    void loadCatalog();
  }, [loadCatalog]);

  useEffect(() => {
    setSearchText(filters.query);
    void load();
  }, [filters, load]);

  useEffect(() => {
    if (!list || !list.items.some((d) => d.processing.state === "RECEIVED" || d.processing.state === "SUBMITTED")) {
      return;
    }
    const timer = window.setTimeout(() => void load(), 4000);
    return () => window.clearTimeout(timer);
  }, [list, load]);

  const categories = catalog?.categories ?? [];
  const folderName = (folderId: string) =>
    categories.find((category) => category.id === folderId)?.name ?? "";

  const toggleSubject = (value: string) =>
    setUploadSubjects((current) =>
      current.includes(value) ? current.filter((entry) => entry !== value) : [...current, value],
    );

  const onUpload = async (event: FormEvent) => {
    event.preventDefault();
    if (!file) return;
    setUploading(true);
    setUploadError(null);
    setMessage(null);
    try {
      const form = new FormData();
      form.append("file", file, file.name);
      uploadSubjects.forEach((subject) => form.append("subjects", subject));
      const target = uploadFolder ? `?categoryId=${encodeURIComponent(uploadFolder)}` : "";
      const response = await fetch(`/api/documents${target}`, {
        method: "POST",
        body: form,
        credentials: "same-origin",
        headers: {
          "X-XSRF-TOKEN": decodeURIComponent(
            document.cookie
              .split("; ")
              .find((cookie) => cookie.startsWith("XSRF-TOKEN="))
              ?.substring(11) ?? "",
          ),
        },
      });
      const body = await response.json().catch(() => ({}));
      if (!response.ok) {
        const { ApiError } = await import("../api/client");
        throw new ApiError(response.status, body);
      }
      const result = body as { document: { title: string }; duplicate: boolean };
      setMessage(
        result.duplicate
          ? t("documents.duplicate", { title: result.document.title })
          : t("documents.uploaded", { title: result.document.title }),
      );
      setFile(null);
      await Promise.all([load(), loadCatalog()]);
    } catch (failure) {
      setUploadError(problemMessage(t, failure));
    } finally {
      setUploading(false);
    }
  };

  const moveSelected = async () => {
    if (selection.length === 0 || !list) return;
    try {
      const documents = selection.map((id) => ({
        documentId: id,
        expectedVersion: list.items.find((item) => item.id === id)?.classification.version ?? 0,
      }));
      const result = unwrap(
        await api.POST("/api/documents/placement", { body: { categoryId: moveTarget, documents } }),
      );
      setMessage(t("documents.moved", { count: result.moved }));
      setSelection([]);
      await Promise.all([load(), loadCatalog()]);
    } catch (failure) {
      setError(problemMessage(t, failure));
      await load();
    }
  };

  const filterCount = activeFilterCount(filters);
  const pages = list ? Math.max(1, Math.ceil(list.matchingDocuments / Math.max(1, list.size))) : 1;

  return (
    <>
      <PageTitle
        actions={
          <span className="flex flex-wrap gap-2">
            <Link className="btn-secondary" to="/documents/folders">
              {t("documents.manageFolders")}
            </Link>
            <Link className="btn-secondary" to="/documents/labels">
              {t("documents.manageLabels")}
            </Link>
          </span>
        }
      >
        {t("documents.title")}
      </PageTitle>

      <details className="card mb-4" data-testid="upload-panel">
        <summary className="min-h-11 cursor-pointer font-semibold" data-testid="upload-panel-toggle">
          {t("documents.upload")}
        </summary>
        <form className="mt-3 flex flex-col gap-3" onSubmit={onUpload} data-testid="upload-form">
          <p className="text-sm text-stone-600 dark:text-stone-400">{t("documents.uploadHint")}</p>
          <label className="label" htmlFor="file">
            {t("documents.chooseFile")}
          </label>
          <input
            id="file"
            type="file"
            accept="application/pdf,.pdf"
            className="field"
            data-testid="upload-input"
            onChange={(event) => setFile(event.target.files?.[0] ?? null)}
          />
          <FolderSelect
            categories={categories}
            value={uploadFolder}
            onChange={setUploadFolder}
            label={t("documents.uploadFolder")}
            id="upload-folder"
          />
          <fieldset>
            <legend className="label">{t("documents.subjects")}</legend>
            <div className="flex flex-wrap gap-3">
              {["HOUSEHOLD", "HOUSE"].map((kind) => (
                <label key={kind} className="flex items-center gap-2 text-sm">
                  <input
                    type="checkbox"
                    checked={uploadSubjects.includes(kind)}
                    onChange={() => toggleSubject(kind)}
                  />
                  {t(`subjects.${kind}`)}
                </label>
              ))}
              {members.map((member) => (
                <label key={member.id} className="flex items-center gap-2 text-sm">
                  <input
                    type="checkbox"
                    checked={uploadSubjects.includes(`MEMBER:${member.id}`)}
                    onChange={() => toggleSubject(`MEMBER:${member.id}`)}
                  />
                  {member.displayName}
                </label>
              ))}
            </div>
            <p className="mt-1 text-xs text-stone-500">{t("subjects.hint")}</p>
          </fieldset>
          <ErrorBox message={uploadError} />
          <div>
            <button type="submit" className="btn-primary" disabled={!file || uploading} data-testid="upload-submit">
              {uploading ? t("documents.uploading") : t("documents.upload")}
            </button>
          </div>
        </form>
      </details>

      {message && <InfoBox>{message}</InfoBox>}
      <ErrorBox message={error} />

      <div className="grid gap-4 md:grid-cols-[16rem_1fr]">
        <div className="flex flex-col gap-2">
          {/* One tree for both layouts: always visible from the medium breakpoint, toggled on a phone. */}
          <button
            type="button"
            className="btn-secondary w-full md:hidden"
            aria-expanded={showFolders}
            aria-controls="folder-panel"
            data-testid="folders-toggle"
            onClick={() => setShowFolders((value) => !value)}
          >
            {t("documents.folders")}
          </button>
          <div id="folder-panel" className={`${showFolders ? "" : "hidden"} md:block`}>
            {catalog && (
              <FolderTree
                categories={categories}
                unsortedCount={catalog.unsortedCount}
                totalDocuments={catalog.totalDocuments}
                selected={filters.category}
                onSelect={(category) => {
                  apply({ ...filters, category, page: 0 });
                  setShowFolders(false);
                }}
              />
            )}
          </div>
        </div>

        <section aria-label={t("documents.title")}>
          <Breadcrumbs
            categories={categories}
            selected={filters.category}
            onSelect={(category) => apply({ ...filters, category, page: 0 })}
          />
          <form
            className="mb-2 flex gap-2"
            role="search"
            onSubmit={(event) => {
              event.preventDefault();
              apply({ ...filters, query: searchText, page: 0 });
            }}
          >
            <input
              type="search"
              className="field"
              placeholder={t("documents.searchPlaceholder")}
              aria-label={t("documents.search")}
              value={searchText}
              onChange={(event) => setSearchText(event.target.value)}
              data-testid="search-input"
            />
            <button type="submit" className="btn-secondary">
              {t("documents.search")}
            </button>
          </form>

          <details className="card mb-3" open={filterCount > 0}>
            <summary className="min-h-11 cursor-pointer text-sm font-semibold" data-testid="filters-toggle">
              {t("documents.filters")}
              {filterCount > 0 && ` · ${t("documents.filtersActive", { count: filterCount })}`}
            </summary>
            <div className="mt-2 flex flex-col gap-3">
              {list && (
                <FacetFilters
                  filters={filters}
                  facets={list.facets}
                  labels={labels}
                  members={members}
                  onChange={apply}
                />
              )}
              {filterCount > 0 && (
                <div>
                  <button
                    type="button"
                    className="btn-ghost"
                    data-testid="clear-filters"
                    onClick={() => apply(withoutFilters(filters))}
                  >
                    {t("documents.clearFilters")}
                  </button>
                </div>
              )}
            </div>
          </details>

          {!list && !error && <Loading />}
          {list && (
            <div className="flex flex-col gap-2">
              {list.contentSearch === "UNAVAILABLE" && (
                <p className="alert-warn" role="alert">
                  {t("documents.contentSearchUnavailable")}
                </p>
              )}
              {list.notContentIndexed > 0 && filters.query.trim() && (
                <p className="alert-info" role="status">
                  {t("documents.notContentIndexed", { count: list.notContentIndexed })}
                </p>
              )}
              <div className="flex flex-wrap items-center justify-between gap-2">
                <p className="text-sm text-stone-500" data-testid="result-count">
                  {t("documents.results", { count: list.matchingDocuments })}
                  {list.matchingDocuments !== list.totalDocuments &&
                    ` · ${t("documents.count", { count: list.totalDocuments })}`}
                </p>
                <label className="flex items-center gap-2 text-sm">
                  {t("documents.sort")}
                  <select
                    className="rounded-lg border border-stone-300 bg-white px-2 py-1 dark:border-stone-700 dark:bg-stone-900"
                    value={filters.sort}
                    aria-label={t("documents.sort")}
                    onChange={(event) =>
                      apply({ ...filters, sort: event.target.value as CatalogFilters["sort"], page: 0 })
                    }
                  >
                    {(["NEWEST", "OLDEST", "TITLE"] as const).map((order) => (
                      <option key={order} value={order}>
                        {t(`documents.sort${order}`)}
                      </option>
                    ))}
                  </select>
                </label>
              </div>

              {selection.length > 0 && (
                <div className="card flex flex-wrap items-end gap-2" data-testid="selection-bar">
                  <p className="text-sm font-medium">{t("documents.selected", { count: selection.length })}</p>
                  <FolderSelect
                    categories={categories}
                    value={moveTarget}
                    onChange={setMoveTarget}
                    label={t("documents.moveSelected")}
                    id="move-target"
                  />
                  <button type="button" className="btn-primary" onClick={moveSelected} data-testid="move-selected">
                    {t("documents.moveSelected")}
                  </button>
                  <button type="button" className="btn-ghost" onClick={() => setSelection([])}>
                    {t("documents.clearSelection")}
                  </button>
                </div>
              )}

              {list.items.length === 0 && (
                <EmptyState>
                  {filterCount > 0
                    ? t("documents.noResultsFiltered")
                    : list.totalDocuments === 0
                      ? t("documents.empty")
                      : t("documents.noResults", { query: filters.query })}
                </EmptyState>
              )}
              {list.items.map((document) => (
                <DocumentCard
                  key={document.id}
                  document={document}
                  folderName={folderName}
                  selected={selection.includes(document.id)}
                  onSelectedChange={(checked) =>
                    setSelection((current) =>
                      checked ? [...current, document.id] : current.filter((id) => id !== document.id),
                    )
                  }
                />
              ))}

              {pages > 1 && (
                <nav className="flex items-center justify-between gap-2" aria-label={t("documents.pageOf", {
                  page: filters.page + 1,
                  pages,
                })}>
                  <button
                    type="button"
                    className="btn-secondary"
                    disabled={filters.page === 0}
                    onClick={() => apply({ ...filters, page: filters.page - 1 })}
                  >
                    {t("documents.previousPage")}
                  </button>
                  <span className="text-sm text-stone-500">
                    {t("documents.pageOf", { page: filters.page + 1, pages })}
                  </span>
                  <button
                    type="button"
                    className="btn-secondary"
                    disabled={filters.page + 1 >= pages}
                    data-testid="next-page"
                    onClick={() => apply({ ...filters, page: filters.page + 1 })}
                  >
                    {t("documents.nextPage")}
                  </button>
                </nav>
              )}
            </div>
          )}
          {filters.category === UNSORTED && list?.items.length === 0 && (
            <p className="mt-2 text-sm text-stone-500">{t("documents.unsortedHint")}</p>
          )}
        </section>
      </div>
    </>
  );
}
