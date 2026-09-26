import { useCallback, useEffect, useMemo, useState, type FormEvent } from "react";
import { Link, useSearchParams } from "react-router";
import { useTranslation } from "react-i18next";
import { api, unwrap } from "../api/client";
import { uploadDocument } from "../api/upload";
import type { CategoryListResponse, DocumentDto, DocumentListResponse, LabelDto } from "../api/types";
import { useSession } from "../auth/SessionContext";
import { Breadcrumbs } from "../components/Breadcrumbs";
import { DocumentCard } from "../components/DocumentCard";
import { FacetFilters } from "../components/FacetFilters";
import { FileCapture } from "../components/FileCapture";
import { FolderSelect } from "../components/FolderSelect";
import { FolderTree } from "../components/FolderTree";
import { ScanPages } from "../components/ScanPages";
import { UploadSubjects } from "../components/UploadSubjects";
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
import { todayIso } from "../lib/format";
import { problemMessage } from "../lib/problem";
import { awaitingTextCheck } from "../lib/textRecognition";
import { CONTENT_TYPES, detectFormat, localRejection, uploadName, type UploadFormat } from "../lib/uploadFormat";
import { MAX_PAGES } from "../scan/draft";
import { useScanDraft } from "../scan/useScanDraft";

/** Upper bound of one explicit analysis request; matches the server's bulk limit. */
const MAX_ANALYSIS_SELECTION = 10;

export function DocumentsPage() {
  const { t } = useTranslation();
  const { member, members } = useSession();
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
  const [fileFormat, setFileFormat] = useState<UploadFormat | null>(null);
  const [uploadSubjects, setUploadSubjects] = useState<string[]>([]);
  const [uploadFolder, setUploadFolder] = useState<string | null>(null);
  const [showFolders, setShowFolders] = useState(false);
  // "Upload a new photo" on a document without recognized text and "Hochladen" in the desktop sidebar open the upload
  // panel. Filter changes that rewrite the query never close it under the member's hands.
  const uploadRequested = params.get("upload") === "1";
  const [uploadOpen, setUploadOpen] = useState(uploadRequested);
  useEffect(() => {
    if (uploadRequested) setUploadOpen(true);
  }, [uploadRequested]);
  // On first use the collection is empty and uploading is the only thing to do, so the panel starts open. It stays
  // open after the first upload instead of closing under the member's hands.
  const [firstUse, setFirstUse] = useState(false);
  // "Scan 2026-09-25 10.15": the local date and time the first page was taken, as the initial name and title.
  const scanName = useCallback(() => {
    const now = new Date();
    const time = `${String(now.getHours()).padStart(2, "0")}.${String(now.getMinutes()).padStart(2, "0")}`;
    return t("scan.name", { date: todayIso(), time });
  }, [t]);
  const scan = useScanDraft(member?.id ?? null, scanName);
  const scanning = scan.pages.length > 0;
  // A single new photo (the camera, or one image from the gallery) is shown with its detected sheet right away.
  const [checkId, setCheckId] = useState<string | null>(null);

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
      const loaded = unwrap(categories);
      setCatalog(loaded);
      setFirstUse((current) => current || loaded.totalDocuments === 0);
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
    const pending = (d: DocumentDto) =>
      d.processing.state === "RECEIVED" || d.processing.state === "SUBMITTED" || awaitingTextCheck(d);
    if (!list || !list.items.some(pending)) {
      return;
    }
    const timer = window.setTimeout(() => void load(), 4000);
    return () => window.clearTimeout(timer);
  }, [list, load]);

  const categories = catalog?.categories ?? [];
  const folderName = (folderId: string) =>
    categories.find((category) => category.id === folderId)?.name ?? "";

  const selectFile = async (chosen: File) => {
    setMessage(null);
    const format = await detectFormat(chosen);
    const rejection = localRejection(chosen, format);
    setFile(chosen);
    setFileFormat(rejection ? null : format);
    setUploadError(rejection ? t(`errors.${rejection}`) : null);
  };

  const addPhotos = (photos: File[]) => {
    setMessage(null);
    clearFile();
    const added = scan.addPhotos(photos);
    setCheckId(added.length === 1 ? added[0] : null);
  };

  const clearFile = () => {
    setFile(null);
    setFileFormat(null);
    setUploadError(null);
  };

  /** The scan as one PDF, or the chosen file unchanged; null (with the reason shown) when it cannot be sent. */
  const uploadable = async (): Promise<File | null> => {
    if (scanning) {
      // The PDF is built again for every attempt; the same pages give the same bytes, so a retry after a lost
      // answer is recognized by the server as the document it already stored.
      const pdf = await scan.toPdf();
      const rejection = localRejection(pdf, "pdf");
      if (rejection) setUploadError(t(`errors.${rejection}`));
      return rejection ? null : pdf;
    }
    if (!file || !fileFormat) return null;
    const named = uploadName(file.name, t("share.fallbackName", { date: todayIso() }), fileFormat);
    return named === file.name ? file : new File([file], named, { type: CONTENT_TYPES[fileFormat] });
  };

  const onUpload = async (event: FormEvent) => {
    event.preventDefault();
    setUploading(true);
    setUploadError(null);
    setMessage(null);
    try {
      const upload = await uploadable();
      if (!upload) return;
      const result = await uploadDocument(upload, { subjects: uploadSubjects, categoryId: uploadFolder });
      setMessage(
        result.duplicate
          ? t(result.document.trash ? "documents.duplicateTrashed" : "documents.duplicate", {
              title: result.document.title,
            })
          : t("documents.uploaded", { title: result.document.title }),
      );
      clearFile();
      if (scanning) await scan.clear();
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

  const analyzeSelected = async () => {
    if (selection.length === 0) return;
    try {
      const result = unwrap(await api.POST("/api/documents/analysis", { body: { documentIds: selection } }));
      setMessage(
        result.skipped.length > 0
          ? t("analysis.bulkPartly", { count: result.requested.length, skipped: result.skipped.length })
          : t("analysis.bulkStarted", { count: result.requested.length }),
      );
      setError(null);
      setSelection([]);
      await load();
    } catch (failure) {
      setError(problemMessage(t, failure));
    }
  };

  const filterCount = activeFilterCount(filters);
  const pages = list ? Math.max(1, Math.ceil(list.matchingDocuments / Math.max(1, list.size))) : 1;

  return (
    <>
      <PageTitle>{t("documents.title")}</PageTitle>

      {/* The upload comes first (issue #83, F8); where the other controls belong is issue #108. */}
      <details className="card mb-4" data-testid="upload-panel" open={uploadOpen || scanning || firstUse || undefined}>
        <summary className="min-h-11 cursor-pointer font-semibold" data-testid="upload-panel-toggle">
          {t("documents.upload")}
        </summary>
        <form className="mt-3 flex flex-col gap-3" onSubmit={onUpload} data-testid="upload-form">
          {!scanning && <p className="text-sm text-stone-600 dark:text-stone-400">{t("documents.uploadHint")}</p>}
          {scan.restored > 0 && scanning && (
            <InfoBox>{t("scan.restored", { count: scan.restored })}</InfoBox>
          )}
          <FileCapture
            file={file}
            format={fileFormat}
            pageCount={scan.pages.length}
            disabled={uploading}
            onPhotos={addPhotos}
            onFile={(chosen) => void selectFile(chosen)}
            onClear={clearFile}
          />
          {scan.problem && <ErrorBox message={t(`scan.problem.${scan.problem}`, { max: MAX_PAGES })} />}
          {scanning && (
            <ScanPages scan={scan} disabled={uploading} checkId={checkId} onChecked={() => setCheckId(null)} />
          )}
          <FolderSelect
            categories={categories}
            value={uploadFolder}
            onChange={setUploadFolder}
            label={t("documents.uploadFolder")}
            id="upload-folder"
          />
          <UploadSubjects value={uploadSubjects} onChange={setUploadSubjects} />
          <ErrorBox message={uploadError} />
          <div>
            <button
              type="submit"
              className="btn-primary"
              disabled={uploading || (scanning ? scan.processing : !file || !fileFormat)}
              data-testid="upload-submit"
            >
              {uploading
                ? t("documents.uploading")
                : scanning
                  ? t("scan.save", { count: scan.pages.length })
                  : t("documents.uploadSubmit")}
            </button>
          </div>
        </form>
      </details>

      {message && <InfoBox>{message}</InfoBox>}
      <ErrorBox message={error} />

      <p className="mb-4 flex flex-wrap gap-2">
        <Link className="btn-secondary" to="/documents/folders">
          {t("documents.manageFolders")}
        </Link>
        <Link className="btn-secondary" to="/documents/labels">
          {t("documents.manageLabels")}
        </Link>
        <Link className="btn-ghost" to="/trash" data-testid="open-trash">
          {t("trash.open")}
        </Link>
      </p>

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
                  <button
                    type="button"
                    className="btn-secondary"
                    onClick={analyzeSelected}
                    disabled={selection.length > MAX_ANALYSIS_SELECTION}
                    data-testid="analyze-selected"
                  >
                    {t("analysis.analyzeSelected")}
                  </button>
                  <button type="button" className="btn-ghost" onClick={() => setSelection([])}>
                    {t("documents.clearSelection")}
                  </button>
                  {selection.length > MAX_ANALYSIS_SELECTION && (
                    <p className="w-full text-xs text-stone-500">
                      {t("analysis.selectionLimit", { max: MAX_ANALYSIS_SELECTION })}
                    </p>
                  )}
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
