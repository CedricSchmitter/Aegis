import { useEffect, useMemo, useRef, useState, type ChangeEvent } from "react";
import { Link, useNavigate, useSearchParams } from "react-router";
import { useTranslation } from "react-i18next";
import { api, unwrap } from "../api/client";
import type { CategoryDto } from "../api/types";
import { useSession } from "../auth/SessionContext";
import { BackLink, BigTitle, ErrorBox, InfoBox, Loading } from "../components/common";
import { PageEditor as CornerEditor } from "../components/PageEditor";
import { AddDocumentSheet } from "../components/pageEdit/AddDocumentSheet";
import { OutputCard } from "../components/pageEdit/OutputCard";
import { PageEditResult } from "../components/pageEdit/PageEditResult";
import { previewUrl, rotationStyle } from "../components/pageEdit/PageThumb";
import { Icon } from "../components/shell/icons";
import { useDesktop } from "../components/shell/ShellData";
import { Dialog, Overlay } from "../components/ui";
import { formatDate, todayIso } from "../lib/format";
import { PHOTO_ACCEPT } from "../lib/uploadFormat";
import {
  LIMITS,
  locate,
  movePage,
  movePageTo,
  pageCount,
  planProblems,
  removePage,
  removeScanPage,
  scanOrder,
  sourceOf,
  usedDocuments,
  type PlanPage,
  type TitleWords,
} from "../pageEdit/plan";
import { useDragReorder } from "../pageEdit/useDragReorder";
import { usePagePlan, type SourceRefusal } from "../pageEdit/usePagePlan";
import { MAX_PAGES } from "../scan/draft";

/** Documents to start with, as `?add=<id>,<id>` from the document list's selection or a document's page. */
function parseAdd(value: string | null): string[] {
  return (value ?? "").split(",").map((id) => id.trim()).filter(Boolean);
}

/**
 * The page editor (ADR-0024): one view to split documents with markers between pages, merge pages of
 * several documents, add newly photographed pages, reorder, rotate and remove pages. Every new document is created
 * from the plan by the server; the sources stay unchanged. The plan is kept on the phone until it is sent.
 */
export function PageEditorPage() {
  const { t, i18n } = useTranslation();
  const { member } = useSession();
  const desktop = useDesktop();
  const navigate = useNavigate();
  const [params] = useSearchParams();
  const editor = usePagePlan(member?.id ?? null);
  const { plan, scan } = editor;
  const [categories, setCategories] = useState<CategoryDto[]>([]);
  const [refusals, setRefusals] = useState<SourceRefusal[]>([]);
  const [adding, setAdding] = useState(false);
  const [pendingAdd, setPendingAdd] = useState<string[] | null>(null);
  const [viewing, setViewing] = useState<PlanPage | null>(null);
  const [cornerPage, setCornerPage] = useState<{ id: string; purpose: "check" | "edit" } | null>(null);
  const [confirmDiscard, setConfirmDiscard] = useState(false);
  const [announcement, setAnnouncement] = useState("");
  const camera = useRef<HTMLInputElement>(null);
  const gallery = useRef<HTMLInputElement>(null);
  const startHandled = useRef(false);

  const words: TitleWords = useMemo(
    () => ({
      newPages: t("pageEdit.newPagesTitle", { date: formatDate(todayIso(), i18n.language) }),
      part: (title, number) => t("pageEdit.partTitle", { title, number }),
    }),
    [i18n.language, t],
  );

  useEffect(() => {
    void api.GET("/api/categories").then(
      (result) => setCategories(unwrap(result).categories),
      () => setCategories([]),
    );
  }, []);

  const addDocuments = async (ids: readonly string[]) => {
    const refused = await editor.addDocuments(ids);
    setRefusals((current) => [...current.filter((entry) => !ids.includes(entry.documentId)), ...refused]);
  };

  // Documents handed over in the address are added once; the address is cleaned so a reload does not add them again.
  const planLoaded = plan !== null;
  useEffect(() => {
    if (!planLoaded || !plan || startHandled.current) return;
    startHandled.current = true;
    const ids = parseAdd(params.get("add"));
    if (ids.length === 0) return;
    navigate("/documents/pages", { replace: true });
    if (pageCount(plan) > 0 || plan.pending) setPendingAdd(ids);
    else void addDocuments(ids);
    // Runs once, when the stored plan is known.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [planLoaded]);

  const drag = useDragReorder((pageKey, target) => editor.change((current) => movePageTo(current, pageKey, target.outputKey, target.position)));

  if (!member || !plan) return <Loading />;

  const sending = editor.sending;
  const accepted = plan.pending || (editor.operation !== null && editor.operation.state !== "FAILED");
  const disabled = sending || accepted;
  const problems = planProblems(plan, words);
  const used = usedDocuments(plan);
  const newPages = scanOrder(plan).length;
  const total = pageCount(plan);

  const move = (page: PlanPage, delta: -1 | 1) => {
    const next = movePage(plan, page.key, delta);
    editor.change(() => next);
    const at = locate(next, page.key);
    if (at) setAnnouncement(t("pageEdit.moved", { number: at.position + 1, output: at.output + 1 }));
  };

  const remove = (page: PlanPage) => {
    editor.change((current) => removePage(current, page.key));
    if (page.source === "SCAN") editor.forgetScanPage(page.scanPageId);
  };

  const open = (page: PlanPage) => {
    if (page.source === "SCAN") setCornerPage({ id: page.scanPageId, purpose: "edit" });
    else setViewing(page);
  };

  const pickPhotos = (event: ChangeEvent<HTMLInputElement>) => {
    const chosen = [...(event.target.files ?? [])];
    event.target.value = "";
    if (chosen.length === 0) return;
    const added = editor.addPhotos(chosen);
    // A single new photo is shown with its detected sheet right away, as in the upload's scan.
    if (added.length === 1) setCornerPage({ id: added[0], purpose: "check" });
  };

  const cornerScanPage = cornerPage ? scan.pages.find((page) => page.id === cornerPage.id) : undefined;
  const cornerNumber = cornerPage ? scanOrder(plan).indexOf(cornerPage.id) + 1 : 0;
  const closeCorners = () => setCornerPage(null);

  return (
    <div className="flex flex-col gap-4" data-testid="page-edit">
      <BackLink to="/documents">{t("documents.title")}</BackLink>
      <BigTitle eyebrow={t("pageEdit.eyebrow")}>{t("pageEdit.title")}</BigTitle>
      <p className="-mt-2 text-sm text-text-3">{t("pageEdit.intro")}</p>

      {editor.restored && total > 0 && !accepted && <InfoBox>{t("pageEdit.restored", { count: total })}</InfoBox>}
      {refusals.map((refusal) => (
        <p key={refusal.documentId} className="alert-error" role="alert" data-testid="page-edit-refusal">
          {refusal.title
            ? t("pageEdit.refused", { title: refusal.title, reason: t(`errors.${refusal.code}`, { defaultValue: t("errors.generic") }) })
            : t(`errors.${refusal.code}`, { defaultValue: t("errors.generic") })}
        </p>
      ))}
      {editor.failure && (
        <div data-testid="page-edit-failure">
          <ErrorBox
            message={t("pageEdit.failed", { reason: t(`errors.${editor.failure}`, { defaultValue: t("errors.generic") }) })}
          />
        </div>
      )}

      {accepted ? (
        <PageEditResult
          operation={editor.operation}
          onStartOver={() => {
            editor.startOver();
            setRefusals([]);
          }}
        />
      ) : (
        <>
          {plan.outputs.length === 0 && !editor.addingDocuments && (
            <p className="card text-text-3" data-testid="page-edit-empty">
              {t("pageEdit.empty")}
            </p>
          )}

          {plan.outputs.map((output, index) => (
            <OutputCard
              key={output.key}
              plan={plan}
              index={index}
              words={words}
              categories={categories}
              scanPages={scan.pages}
              disabled={disabled}
              drag={drag}
              onChange={editor.change}
              onMove={move}
              onRemove={remove}
              onOpen={open}
            />
          ))}
          <p className="sr-only" aria-live="polite">
            {announcement}
          </p>
          {editor.addingDocuments && (
            <p className="text-sm text-text-3" aria-busy="true">
              {t("pageEdit.loadingPages")}
            </p>
          )}

          <section className="flex flex-col gap-2" aria-labelledby="page-edit-add-heading">
            <h2 id="page-edit-add-heading" className="ui-sec">
              {t("pageEdit.add.heading")}
            </h2>
            <div className="grid gap-2 sm:grid-cols-3">
              <button type="button" className="btn-secondary" disabled={disabled} onClick={() => setAdding(true)} data-testid="page-edit-add-document">
                <Icon name="documents" className="h-[18px] w-[18px]" />
                {t("pageEdit.add.document")}
              </button>
              {!desktop && (
                <button type="button" className="btn-secondary" disabled={disabled} onClick={() => camera.current?.click()} data-testid="page-edit-take-photo">
                  <Icon name="camera" className="h-[18px] w-[18px]" />
                  {t("pageEdit.add.photo")}
                </button>
              )}
              <button type="button" className="btn-secondary" disabled={disabled} onClick={() => gallery.current?.click()} data-testid="page-edit-choose-image">
                <Icon name="image" className="h-[18px] w-[18px]" />
                {t("pageEdit.add.images")}
              </button>
            </div>
            <input ref={camera} type="file" accept="image/*" capture="environment" hidden onChange={pickPhotos} data-testid="page-edit-camera-input" />
            <input ref={gallery} type="file" accept={PHOTO_ACCEPT} multiple hidden onChange={pickPhotos} data-testid="page-edit-image-input" />
            {scan.problem && <ErrorBox message={t(`scan.problem.${scan.problem}`, { max: MAX_PAGES })} />}
          </section>

          {used.length > 0 && (
            <section className="flex flex-col gap-2" aria-labelledby="page-edit-sources-heading" data-testid="page-edit-sources">
              <h2 id="page-edit-sources-heading" className="ui-sec">
                {t("pageEdit.sources.heading")}
              </h2>
              <p className="text-sm text-text-3">{t("pageEdit.sources.kept")}</p>
              <ul className="flex flex-col gap-2">
                {used.map((id) => {
                  const source = sourceOf(plan, id);
                  return (
                    <li key={id} className="flex flex-col gap-1">
                      <Link to={`/documents/${id}`} className="text-sm font-medium break-words text-data hover:underline">
                        {source?.title ?? id}
                      </Link>
                      {source?.warnings.map((warning) => (
                        <p key={warning} className="alert-warn text-sm" data-testid="page-edit-warning" data-warning={warning}>
                          {t(`pageEdit.warning.${warning}`, { defaultValue: t("pageEdit.warning.other") })}
                        </p>
                      ))}
                    </li>
                  );
                })}
              </ul>
            </section>
          )}

          {total > 0 && (
            <section className="flex flex-col gap-3" aria-label={t("pageEdit.submitSection")}>
              <p className="font-mono text-xs text-text-3" data-testid="page-edit-counts">
                {t("pageEdit.counts", {
                  outputs: plan.outputs.length,
                  pages: total,
                  sources: used.length,
                  newPages,
                })}
              </p>
              {problems
                .filter((problem) => problem !== "pageEdit.plan.empty")
                .map((problem) => (
                  <p key={problem} className="alert-warn text-sm" data-testid="page-edit-problem">
                    {t(`errors.${problem}`, { defaultValue: t("errors.generic") })}
                  </p>
                ))}
              {scan.processing && <p className="text-sm text-text-3">{t("pageEdit.waitForPhotos")}</p>}
              <button
                type="button"
                className="btn-primary w-full"
                disabled={disabled || problems.length > 0 || scan.processing || editor.addingDocuments}
                onClick={() => void editor.submit(words)}
                data-testid="page-edit-submit"
              >
                {sending ? t("pageEdit.sending") : t("pageEdit.submit", { count: plan.outputs.length })}
              </button>
              <div>
                {confirmDiscard ? (
                  <span className="flex flex-wrap items-center gap-2">
                    <span className="text-sm">{t("pageEdit.discardQuestion")}</span>
                    <button
                      type="button"
                      className="btn-secondary"
                      disabled={disabled}
                      onClick={() => {
                        setConfirmDiscard(false);
                        setRefusals([]);
                        void editor.discard();
                      }}
                      data-testid="page-edit-discard-confirm"
                    >
                      {t("pageEdit.discardConfirm")}
                    </button>
                    <button type="button" className="btn-ghost" onClick={() => setConfirmDiscard(false)}>
                      {t("app.cancel")}
                    </button>
                  </span>
                ) : (
                  <button type="button" className="btn-ghost -ml-4" disabled={disabled} onClick={() => setConfirmDiscard(true)} data-testid="page-edit-discard">
                    {t("pageEdit.discard")}
                  </button>
                )}
              </div>
              <p className="text-xs text-text-3">
                {t("pageEdit.limits", { outputs: LIMITS.outputs, pages: LIMITS.pagesPerOutput, sources: LIMITS.sources, newPages: LIMITS.newPages })}
              </p>
            </section>
          )}
        </>
      )}

      <AddDocumentSheet
        open={adding}
        onClose={() => setAdding(false)}
        inPlan={used}
        onAdd={(id) => {
          setAdding(false);
          void addDocuments([id]);
        }}
      />

      <Dialog
        open={pendingAdd !== null}
        onClose={() => setPendingAdd(null)}
        title={t("pageEdit.continue.title")}
        actions={
          <>
            <button
              type="button"
              className="btn-secondary"
              onClick={() => {
                const ids = pendingAdd ?? [];
                setPendingAdd(null);
                void editor.discard().then(() => addDocuments(ids));
              }}
              data-testid="page-edit-continue-new"
            >
              {t("pageEdit.continue.startNew")}
            </button>
            <button
              type="button"
              className="btn-primary"
              onClick={() => {
                const ids = pendingAdd ?? [];
                setPendingAdd(null);
                if (!plan.pending) void addDocuments(ids);
              }}
              data-testid="page-edit-continue-add"
            >
              {t("pageEdit.continue.add")}
            </button>
          </>
        }
      >
        <p className="text-sm">{t("pageEdit.continue.text", { count: total })}</p>
      </Dialog>

      {viewing?.source === "DOCUMENT" && (
        <Overlay onClose={() => setViewing(null)} className="items-center gap-3" aria-label={t("pageEdit.view.title")} data-testid="page-edit-view">
          <p className="self-start text-sm text-text-2">
            {t("pageEdit.page.fromDocument", { title: sourceOf(plan, viewing.documentId)?.title ?? "", page: viewing.pageIndex + 1 })}
          </p>
          <div className="flex min-h-0 flex-1 items-center justify-center">
            <img
              src={previewUrl(viewing.documentId, viewing.pageIndex)}
              alt={t("pageEdit.view.image", { page: viewing.pageIndex + 1 })}
              className={viewing.rotation % 180 === 0 ? "max-h-[70vh] max-w-full" : "max-h-[90vw] max-w-[70vh]"}
              style={rotationStyle(viewing.rotation)}
            />
          </div>
          <button type="button" className="btn-secondary" onClick={() => setViewing(null)}>
            {t("app.close")}
          </button>
        </Overlay>
      )}

      {cornerPage && cornerScanPage && (
        <CornerEditor
          key={cornerScanPage.id}
          page={cornerScanPage}
          number={cornerNumber}
          purpose={cornerPage.purpose}
          onApply={(corners) => {
            scan.applyCorners(cornerScanPage.id, corners);
            closeCorners();
          }}
          onKeepPhoto={() => {
            scan.keepPhoto(cornerScanPage.id);
            closeCorners();
          }}
          onRemove={() => {
            const id = cornerScanPage.id;
            editor.change((current) => removeScanPage(current, id));
            editor.forgetScanPage(id);
            closeCorners();
          }}
          onClose={closeCorners}
        />
      )}
    </div>
  );
}
