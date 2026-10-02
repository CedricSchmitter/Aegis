import { useCallback, useEffect, useMemo, useState, type DragEvent } from "react";
import { Link, useSearchParams } from "react-router";
import { useTranslation } from "react-i18next";
import { api, unwrap } from "../api/client";
import type { CategoryListResponse, DocumentDto, DocumentListResponse, LabelDto } from "../api/types";
import { useSession } from "../auth/SessionContext";
import { Breadcrumbs } from "../components/Breadcrumbs";
import { BigTitle, EmptyState, ErrorBox, InfoBox, Loading } from "../components/common";
import { DocumentPanel } from "../components/documents/DocumentPanel";
import { DocumentCard, DocumentTable } from "../components/documents/DocumentRows";
import { FilterSheet } from "../components/documents/FilterSheet";
import { FolderRail } from "../components/documents/FolderRail";
import { UploadSheet } from "../components/documents/UploadSheet";
import { ActiveFilters } from "../components/FacetFilters";
import { FolderSelect } from "../components/FolderSelect";
import { ContextPanel, usePanelShown } from "../components/shell/ContextPanel";
import { Icon } from "../components/shell/icons";
import { CATALOG_CHANGED, useDesktop } from "../components/shell/ShellData";
import { SearchField } from "../components/ui";
import {
  UNSORTED,
  facetFilterCount,
  parseFilters,
  toApiQuery,
  toSearchParams,
  withoutFacets,
  type CatalogFilters,
} from "../lib/catalogSearch";
import { todayIso } from "../lib/format";
import { problemMessage } from "../lib/problem";
import { awaitingTextCheck } from "../lib/textRecognition";
import { useScanDraft } from "../scan/useScanDraft";

/** Upper bound of one explicit analysis request; matches the server's bulk limit. */
const MAX_ANALYSIS_SELECTION = 10;

/** Tells the sidebar that folder counts changed. */
const catalogChanged = () => window.dispatchEvent(new Event(CATALOG_CHANGED));

/**
 * The document list (issue #27, #108). Phone: upload as the one primary action, search, the folder rail with
 * "Manage folders", the filter with "Manage labels", cards and the trash link at the end. Desktop: the folders live
 * in the sidebar and the search in the header; a table, the selected document in the context panel from 1280 px
 * and a drop target at the end.
 */
export function DocumentsPage() {
  const { t } = useTranslation();
  const { member, members } = useSession();
  const desktop = useDesktop();
  const panelShown = usePanelShown();
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
  const [filtersOpen, setFiltersOpen] = useState(false);
  const [previewId, setPreviewId] = useState<string | null>(null);
  const [droppedFile, setDroppedFile] = useState<File | null>(null);
  const [dragOver, setDragOver] = useState(false);

  // "Scan 2026-09-25 10.15": the local date and time the first page was taken, as the initial name and title.
  const scanName = useCallback(() => {
    const now = new Date();
    const time = `${String(now.getHours()).padStart(2, "0")}.${String(now.getMinutes()).padStart(2, "0")}`;
    return t("scan.name", { date: todayIso(), time });
  }, [t]);
  const scan = useScanDraft(member?.id ?? null, scanName);
  const scanPages = scan.pages.length;

  // "Hochladen" in the sidebar and "Upload a new photo" on a document without text open the upload with
  // ?upload=1. The parameter is removed again, so the same link opens the sheet a second time.
  const [uploadOpen, setUploadOpen] = useState(false);
  const uploadRequested = params.get("upload") === "1";
  useEffect(() => {
    if (!uploadRequested) return;
    setUploadOpen(true);
    setParams(
      (current) => {
        const next = new URLSearchParams(current);
        next.delete("upload");
        return next;
      },
      { replace: true },
    );
  }, [uploadRequested, setParams]);
  // A scan restored after a reload is shown at once, so its pages are not forgotten.
  useEffect(() => {
    if (scan.restored > 0) setUploadOpen(true);
  }, [scan.restored]);

  const apply = useCallback(
    (next: CatalogFilters) => {
      setParams(toSearchParams(next));
      setSelection([]);
    },
    [setParams],
  );

  const loadCatalog = useCallback(async () => {
    try {
      const [categories, vocabulary] = await Promise.all([api.GET("/api/categories"), api.GET("/api/labels")]);
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
    const pending = (d: DocumentDto) =>
      d.processing.state === "RECEIVED" || d.processing.state === "SUBMITTED" || awaitingTextCheck(d);
    if (!list || !list.items.some(pending)) return;
    const timer = window.setTimeout(() => void load(), 4000);
    return () => window.clearTimeout(timer);
  }, [list, load]);

  const categories = catalog?.categories ?? [];
  const folderName = (folderId: string) => categories.find((category) => category.id === folderId)?.name ?? "";
  const selectFolder = (category: string | null) => apply({ ...filters, category, page: 0 });

  const replaceItem = (document: DocumentDto) =>
    setList((current) =>
      current && { ...current, items: current.items.map((item) => (item.id === document.id ? document : item)) },
    );

  const onUploaded = async (done: string) => {
    setMessage(done);
    await Promise.all([load(), loadCatalog()]);
    catalogChanged();
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
      catalogChanged();
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

  const changeSelection = (id: string, checked: boolean) =>
    setSelection((current) => (checked ? [...current, id] : current.filter((entry) => entry !== id)));

  const onDrop = (event: DragEvent) => {
    event.preventDefault();
    setDragOver(false);
    const dropped = event.dataTransfer.files[0];
    if (!dropped) return;
    setDroppedFile(dropped);
    setUploadOpen(true);
  };

  const facetCount = facetFilterCount(filters);
  const pages = list ? Math.max(1, Math.ceil(list.matchingDocuments / Math.max(1, list.size))) : 1;
  const previewed = panelShown && list ? (list.items.find((item) => item.id === previewId) ?? list.items[0] ?? null) : null;
  const heading = !desktop || !filters.category
    ? t("documents.title")
    : filters.category === UNSORTED
      ? t("documents.unsorted")
      : folderName(filters.category) || t("documents.title");
  const emptyText = !list
    ? ""
    : facetCount > 0
      ? t("documents.noResultsFiltered")
      : list.totalDocuments === 0
        ? t("documents.empty")
        : filters.query.trim()
          ? t("documents.noResults", { query: filters.query.trim() })
          : t("documents.folderEmpty");

  return (
    <>
      {filters.category && catalog && (
        <div className="mb-1.5">
          <Breadcrumbs categories={categories} selected={filters.category} onSelect={selectFolder} />
        </div>
      )}
      <BigTitle count={list && t("documents.total", { count: list.totalDocuments })}>{heading}</BigTitle>

      <div className="flex flex-col gap-4">
        {!desktop && (
          <button type="button" className="btn-primary w-full" onClick={() => setUploadOpen(true)} data-testid="upload-panel-toggle">
            <Icon name="upload" className="h-[18px] w-[18px]" />
            {scanPages > 0 ? t("documents.continueScan", { count: scanPages }) : t("shell.upload")}
          </button>
        )}

        {message && <InfoBox>{message}</InfoBox>}
        <ErrorBox message={error} />

        {!desktop && (
          <form
            role="search"
            onSubmit={(event) => {
              event.preventDefault();
              apply({ ...filters, query: searchText, page: 0 });
            }}
          >
            <SearchField
              label={t("documents.search")}
              placeholder={t("documents.searchPlaceholder")}
              value={searchText}
              onChange={(event) => setSearchText(event.target.value)}
              enterKeyHint="search"
              data-testid="search-input"
            />
          </form>
        )}

        {!desktop && catalog && <FolderRail catalog={catalog} selected={filters.category} onSelect={selectFolder} />}

        <div className="flex flex-wrap items-center gap-2">
          <button
            type="button"
            className="btn-ghost"
            aria-haspopup="dialog"
            onClick={() => setFiltersOpen(true)}
            data-testid="filters-toggle"
          >
            <Icon name="filter" className="h-4 w-4" />
            {t("documents.filters")}
            {facetCount > 0 && (
              <span className="font-mono text-action">
                <span aria-hidden="true">{facetCount}</span>
                <span className="sr-only">{t("documents.filtersActive", { count: facetCount })}</span>
              </span>
            )}
          </button>
          <ActiveFilters filters={filters} labels={labels} members={members} onChange={apply} />
          {facetCount > 0 && (
            <button type="button" className="btn-secondary" onClick={() => apply(withoutFacets(filters))} data-testid="clear-filters">
              {t("documents.clearFilters")}
            </button>
          )}
          {/* Existing documents that were never classified, in one explicitly started run (issue #115). */}
          <Link to="/documents/classify" className="btn-ghost" data-testid="open-bulk-analysis">
            {t("bulkAnalysis.open")}
          </Link>
        </div>

        {!list && !error && <Loading />}
        {list && (
          <section className="flex flex-col gap-2" aria-label={t("documents.title")}>
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
            <div className="ui-sec flex-wrap">
              <span data-testid="result-count">
                {t("documents.results", { count: list.matchingDocuments })}
                {filters.query.trim() && ` · ${t("documents.resultsFor", { query: filters.query.trim() })}`}
              </span>
              <label className="flex items-center gap-2">
                <span className="sr-only">{t("documents.sort")}</span>
                <select
                  className="min-h-11 cursor-pointer bg-transparent pr-1 text-right font-mono text-xs tracking-[0.18em] text-text-3 uppercase"
                  value={filters.sort}
                  onChange={(event) => apply({ ...filters, sort: event.target.value as CatalogFilters["sort"], page: 0 })}
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
                <p className="w-full font-mono text-sm text-action">{t("documents.selected", { count: selection.length })}</p>
                <FolderSelect
                  categories={categories}
                  value={moveTarget}
                  onChange={setMoveTarget}
                  label={t("documents.moveSelected")}
                  id="move-target"
                />
                <button type="button" className="btn-secondary" onClick={moveSelected} data-testid="move-selected">
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
                {/* Pages of the selected documents, in the order they were ticked, open in the page editor. */}
                <Link
                  to={`/documents/pages?add=${selection.map(encodeURIComponent).join(",")}`}
                  className="btn-secondary"
                  data-testid="edit-pages-selected"
                >
                  {t("pageEdit.editSelected")}
                </Link>
                <button type="button" className="btn-ghost" onClick={() => setSelection([])}>
                  {t("documents.clearSelection")}
                </button>
                {selection.length > MAX_ANALYSIS_SELECTION && (
                  <p className="w-full text-xs text-text-3">
                    {t("analysis.selectionLimit", { max: MAX_ANALYSIS_SELECTION })}{" "}
                    <Link to="/documents/classify" className="text-action hover:underline">
                      {t("bulkAnalysis.open")}
                    </Link>
                  </p>
                )}
              </div>
            )}

            {list.items.length === 0 ? (
              <EmptyState>{emptyText}</EmptyState>
            ) : desktop ? (
              <DocumentTable
                items={list.items}
                selection={selection}
                onSelectionChange={changeSelection}
                previewed={previewed?.id ?? null}
                onPreview={panelShown ? setPreviewId : null}
                folderName={folderName}
              />
            ) : (
              <ul className="border-t border-line">
                {list.items.map((document) => (
                  <DocumentCard
                    key={document.id}
                    document={document}
                    folderName={folderName}
                    selected={selection.includes(document.id)}
                    onSelectedChange={(checked) => changeSelection(document.id, checked)}
                  />
                ))}
              </ul>
            )}

            {pages > 1 && (
              <nav className="flex items-center justify-between gap-2" aria-label={t("documents.pageOf", { page: filters.page + 1, pages })}>
                <button
                  type="button"
                  className="btn-secondary"
                  disabled={filters.page === 0}
                  onClick={() => apply({ ...filters, page: filters.page - 1 })}
                >
                  {t("documents.previousPage")}
                </button>
                <span className="font-mono text-sm text-text-3">{t("documents.pageOf", { page: filters.page + 1, pages })}</span>
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
            {filters.category === UNSORTED && list.items.length === 0 && (
              <p className="text-sm text-text-3">{t("documents.unsortedHint")}</p>
            )}

            {desktop ? (
              <button
                type="button"
                className="ui-endlink"
                data-over={dragOver}
                onClick={() => setUploadOpen(true)}
                onDragOver={(event) => {
                  if (![...event.dataTransfer.types].includes("Files")) return;
                  event.preventDefault();
                  setDragOver(true);
                }}
                onDragLeave={() => setDragOver(false)}
                onDrop={onDrop}
                data-testid="upload-panel-toggle"
              >
                <Icon name="upload" className="h-4 w-4" />
                {scanPages > 0 ? t("documents.continueScan", { count: scanPages }) : t("documents.dropToUpload")}
              </button>
            ) : (
              <Link to="/trash" className="ui-endlink" data-testid="open-trash">
                <Icon name="trash" className="h-4 w-4" />
                {t("trash.open")}
              </Link>
            )}
          </section>
        )}
      </div>

      <UploadSheet
        open={uploadOpen}
        onClose={() => setUploadOpen(false)}
        scan={scan}
        categories={categories}
        desktop={desktop}
        droppedFile={droppedFile}
        onDroppedFileTaken={() => setDroppedFile(null)}
        onUploaded={onUploaded}
      />
      <FilterSheet
        open={filtersOpen}
        onClose={() => setFiltersOpen(false)}
        filters={filters}
        facets={list?.facets ?? null}
        matching={list?.matchingDocuments ?? 0}
        labels={labels}
        members={members}
        onChange={apply}
      />
      {previewed && (
        <ContextPanel>
          <DocumentPanel
            key={previewed.id}
            document={previewed}
            categories={categories}
            folderName={folderName}
            onChange={replaceItem}
            onError={setError}
            onMessage={setMessage}
            reload={load}
          />
        </ContextPanel>
      )}
    </>
  );
}
