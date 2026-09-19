import { useCallback, useEffect, useRef, useState } from "react";
import { Link, useSearchParams } from "react-router";
import { useTranslation } from "react-i18next";
import { api, unwrap } from "../api/client";
import type { CategoryDto } from "../api/types";
import { uploadDocument } from "../api/upload";
import { FolderSelect } from "../components/FolderSelect";
import { UploadSubjects } from "../components/UploadSubjects";
import { Badge, EmptyState, ErrorBox, InfoBox, Loading, PageTitle } from "../components/common";
import { formatBytes, todayIso } from "../lib/format";
import { problemMessage } from "../lib/problem";
import {
  listSharedFiles,
  openInbox,
  readSharedFile,
  removeSharedFile,
  type InboxCache,
  type SharedFile,
} from "../share/inbox";
import {
  contentHead,
  hasPdfSignature,
  initialStatus,
  isStored,
  isUnauthenticated,
  needsUpload,
  statusFromError,
  statusFromResponse,
  uploadName,
  type ShareStatus,
} from "../share/sharedUpload";

interface Item {
  file: SharedFile;
  status: ShareStatus;
  /** What was actually read from the inbox; shown with a rejected file. */
  inspected?: { bytes: number; head: string };
}

const BADGE_STATE: Record<ShareStatus["kind"], string> = {
  pending: "QUEUED",
  uploading: "RUNNING",
  saved: "SUCCEEDED",
  duplicate: "SUCCEEDED",
  failed: "FAILED",
  rejected: "FAILED",
};

/**
 * Files shared from other Android apps. They wait in the local share inbox until the server confirmed durable
 * storage, so a login in between, an interrupted connection or leaving the page loses nothing, and a reload does not
 * upload twice.
 */
export function SharePage() {
  const { t, i18n } = useTranslation();
  const [params] = useSearchParams();
  const [inbox, setInbox] = useState<InboxCache | null | undefined>(undefined);
  const [items, setItems] = useState<Item[] | null>(null);
  const [categories, setCategories] = useState<CategoryDto[]>([]);
  const [subjects, setSubjects] = useState<string[]>(["HOUSEHOLD"]);
  const [folder, setFolder] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const saving = useRef(false);

  useEffect(() => {
    let active = true;
    void (async () => {
      const opened = await openInbox();
      if (!active) return;
      setInbox(opened);
      if (!opened) return;
      try {
        const files = await listSharedFiles(opened);
        const checked = await Promise.all(
          files.map(async (file) => {
            const content = await readSharedFile(opened, file);
            const pdf = content !== null && (await hasPdfSignature(content));
            const inspected = { bytes: content?.size ?? 0, head: content ? await contentHead(content) : "" };
            return { file, status: initialStatus(file, pdf), inspected };
          }),
        );
        if (active) setItems(checked);
      } catch {
        if (active) setItems([]);
      }
    })();
    return () => {
      active = false;
    };
  }, []);

  useEffect(() => {
    void api
      .GET("/api/categories")
      .then((result) => setCategories(unwrap(result).categories))
      .catch((failure: unknown) => setError(problemMessage(t, failure)));
  }, [t]);

  const update = useCallback((key: string, status: ShareStatus) => {
    setItems((current) => current?.map((item) => (item.file.key === key ? { ...item, status } : item)) ?? null);
  }, []);

  const drop = useCallback((key: string) => {
    setItems((current) => current?.filter((item) => item.file.key !== key) ?? null);
  }, []);

  const save = async () => {
    if (!inbox || !items || saving.current) return;
    saving.current = true;
    setBusy(true);
    setError(null);
    try {
      for (const item of items.filter((entry) => needsUpload(entry.status))) {
        update(item.file.key, { kind: "uploading" });
        try {
          const content = await readSharedFile(inbox, item.file);
          if (!content) {
            // Saved or discarded elsewhere in the meantime.
            drop(item.file.key);
            continue;
          }
          const name = uploadName(item.file.name, t("share.fallbackName", { date: todayIso() }));
          const response = await uploadDocument(new File([content], name, { type: "application/pdf" }), {
            subjects,
            categoryId: folder,
          });
          update(item.file.key, statusFromResponse(response));
          // If this fails, a later attempt is recognized by the server as the same file and creates no duplicate.
          await removeSharedFile(inbox, item.file).catch(() => undefined);
        } catch (failure) {
          if (isUnauthenticated(failure)) {
            // The app switches to the login page; the files stay in the inbox and this page returns after login.
            update(item.file.key, { kind: "pending" });
            return;
          }
          update(item.file.key, statusFromError(failure));
        }
      }
    } finally {
      saving.current = false;
      setBusy(false);
    }
  };

  const discard = async (targets: Item[]) => {
    if (!inbox) return;
    for (const item of targets) {
      await removeSharedFile(inbox, item.file).catch(() => undefined);
      drop(item.file.key);
    }
  };

  const open = items?.filter((item) => !isStored(item.status)) ?? [];
  const toUpload = open.filter((item) => needsUpload(item.status));
  const stored = items?.filter((item) => isStored(item.status)) ?? [];
  const allStored = stored.length > 0 && toUpload.length === 0 && !busy;

  const statusText = (status: ShareStatus) => {
    switch (status.kind) {
      case "failed":
      case "rejected":
        return t(`errors.${status.code}`, { defaultValue: t("errors.generic") });
      case "saved":
        return t("share.savedAs", { title: status.title });
      case "duplicate":
        return t("share.duplicateOf", { title: status.title });
      default:
        return null;
    }
  };

  return (
    <>
      <PageTitle>{t("share.title")}</PageTitle>
      {params.get("error") === "receive" && <ErrorBox message={t("share.receiveFailed")} />}
      {params.get("error") === "unavailable" && <ErrorBox message={t("share.notReady")} />}
      {inbox === null && <ErrorBox message={t("share.unsupported")} />}
      <ErrorBox message={error} />
      {inbox && items === null && <Loading />}

      {items && items.length === 0 && (
        <EmptyState>
          {params.get("received") === "0" ? t("share.nothingReceived") : t("share.empty")}{" "}
          <Link className="underline" to="/documents">
            {t("share.toDocuments")}
          </Link>
        </EmptyState>
      )}

      {items && items.length > 0 && (
        <div className="flex flex-col gap-4">
          <ul className="flex flex-col gap-2" aria-label={t("share.files")} data-testid="share-files">
            {items.map((item) => {
              const detail = statusText(item.status);
              return (
                <li key={item.file.key} className="card flex flex-col gap-2" data-testid="share-file">
                  <div className="flex flex-wrap items-center justify-between gap-2">
                    <span className="min-w-0 break-words font-medium">{item.file.name || t("share.unnamed")}</span>
                    <Badge state={BADGE_STATE[item.status.kind]} label={t(`share.status.${item.status.kind}`)} />
                  </div>
                  <span className="text-sm text-stone-600 dark:text-stone-400">
                    {formatBytes(item.file.size, t, i18n.language)} · {item.file.type || t("share.unknownType")}
                  </span>
                  {detail && (
                    <p
                      className={`text-sm ${item.status.kind === "failed" || item.status.kind === "rejected" ? "text-red-800 dark:text-red-300" : ""}`}
                      role={item.status.kind === "failed" || item.status.kind === "rejected" ? "alert" : undefined}
                    >
                      {detail}
                    </p>
                  )}
                  {item.status.kind === "rejected" && item.inspected && (
                    <p className="text-xs text-stone-500" data-testid="share-inspected">
                      {t("share.inspected", {
                        size: formatBytes(item.inspected.bytes, t, i18n.language),
                        head: item.inspected.head || "–",
                      })}
                    </p>
                  )}
                  <div className="flex flex-wrap gap-2">
                    {isStored(item.status) && "documentId" in item.status && (
                      <Link className="btn-secondary" to={`/documents/${item.status.documentId}`}>
                        {t("share.openDocument")}
                      </Link>
                    )}
                    {!isStored(item.status) && item.status.kind !== "uploading" && (
                      <button type="button" className="btn-ghost" disabled={busy} onClick={() => void discard([item])}>
                        {t("share.discard")}
                      </button>
                    )}
                  </div>
                </li>
              );
            })}
          </ul>

          {toUpload.length > 0 && (
            <form
              className="card flex flex-col gap-3"
              onSubmit={(event) => {
                event.preventDefault();
                void save();
              }}
            >
              <p className="text-sm text-stone-600 dark:text-stone-400">{t("share.hint")}</p>
              <FolderSelect
                categories={categories}
                value={folder}
                onChange={setFolder}
                label={t("documents.uploadFolder")}
                id="share-folder"
              />
              <UploadSubjects value={subjects} onChange={setSubjects} />
              <div className="flex flex-wrap gap-2">
                <button type="submit" className="btn-primary" disabled={busy} data-testid="share-save">
                  {busy
                    ? t("documents.uploading")
                    : toUpload.some((item) => item.status.kind === "failed")
                      ? t("share.retry", { count: toUpload.length })
                      : t("share.save", { count: toUpload.length })}
                </button>
                <button
                  type="button"
                  className="btn-secondary"
                  disabled={busy}
                  onClick={() => {
                    if (window.confirm(t("share.discardAllConfirm", { count: open.length }))) void discard(open);
                  }}
                >
                  {t("share.discardAll")}
                </button>
              </div>
            </form>
          )}

          {allStored && (
            <div className="flex flex-col gap-2" data-testid="share-done">
              <InfoBox>{t("share.done", { count: stored.length })}</InfoBox>
              <p className="text-sm text-stone-600 dark:text-stone-400">{t("share.returnHint")}</p>
              <div>
                <Link className="btn-primary" to="/documents">
                  {t("share.toDocuments")}
                </Link>
              </div>
            </div>
          )}
        </div>
      )}
    </>
  );
}
