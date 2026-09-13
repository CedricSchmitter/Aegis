import { useCallback, useEffect, useRef, useState, type FormEvent } from "react";
import { useTranslation } from "react-i18next";
import { api, unwrap } from "../api/client";
import type { DocumentListResponse } from "../api/types";
import { useSession } from "../auth/SessionContext";
import { DocumentCard } from "../components/DocumentCard";
import { EmptyState, ErrorBox, InfoBox, Loading, PageTitle } from "../components/common";
import { problemMessage } from "../lib/problem";

export function DocumentsPage() {
  const { t } = useTranslation();
  const { members } = useSession();
  const [query, setQuery] = useState("");
  const [submittedQuery, setSubmittedQuery] = useState("");
  const [list, setList] = useState<DocumentListResponse | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [uploadError, setUploadError] = useState<string | null>(null);
  const [uploadMessage, setUploadMessage] = useState<string | null>(null);
  const [uploading, setUploading] = useState(false);
  const [file, setFile] = useState<File | null>(null);
  const [subjects, setSubjects] = useState<string[]>(["HOUSEHOLD"]);
  const fileInput = useRef<HTMLInputElement>(null);

  const load = useCallback(async (q: string) => {
    try {
      setList(unwrap(await api.GET("/api/documents", { params: { query: q ? { query: q } : {} } })));
      setError(null);
    } catch (failure) {
      setError(problemMessage(t, failure));
    }
  }, [t]);

  useEffect(() => {
    void load(submittedQuery);
  }, [load, submittedQuery]);

  useEffect(() => {
    if (!list || !list.items.some((d) => d.processing.state === "RECEIVED" || d.processing.state === "SUBMITTED")) return;
    const timer = window.setTimeout(() => void load(submittedQuery), 4000);
    return () => window.clearTimeout(timer);
  }, [list, load, submittedQuery]);

  const toggleSubject = (value: string) =>
    setSubjects((current) => (current.includes(value) ? current.filter((s) => s !== value) : [...current, value]));

  const onUpload = async (event: FormEvent) => {
    event.preventDefault();
    if (!file) return;
    setUploading(true);
    setUploadError(null);
    setUploadMessage(null);
    try {
      const form = new FormData();
      form.append("file", file, file.name);
      subjects.forEach((s) => form.append("subjects", s));
      const response = await fetch("/api/documents", {
        method: "POST",
        body: form,
        credentials: "same-origin",
        headers: { "X-XSRF-TOKEN": decodeURIComponent(document.cookie.split("; ").find((c) => c.startsWith("XSRF-TOKEN="))?.substring(11) ?? "") },
      });
      const body = await response.json().catch(() => ({}));
      if (!response.ok) {
        const { ApiError } = await import("../api/client");
        throw new ApiError(response.status, body);
      }
      const result = body as { document: { title: string }; duplicate: boolean };
      setUploadMessage(result.duplicate ? t("documents.duplicate", { title: result.document.title }) : t("documents.uploaded", { title: result.document.title }));
      setFile(null);
      if (fileInput.current) fileInput.current.value = "";
      await load(submittedQuery);
    } catch (failure) {
      setUploadError(problemMessage(t, failure));
    } finally {
      setUploading(false);
    }
  };

  return (
    <>
      <PageTitle>{t("documents.title")}</PageTitle>
      <form className="card mb-4 flex flex-col gap-3" onSubmit={onUpload} data-testid="upload-form">
        <h2 className="font-semibold">{t("documents.upload")}</h2>
        <p className="text-sm text-stone-600 dark:text-stone-400">{t("documents.uploadHint")}</p>
        <label className="label" htmlFor="file">
          {t("documents.chooseFile")}
        </label>
        <input
          id="file"
          ref={fileInput}
          type="file"
          accept="application/pdf,.pdf"
          className="field"
          data-testid="upload-input"
          onChange={(e) => setFile(e.target.files?.[0] ?? null)}
        />
        <fieldset>
          <legend className="label">{t("documents.subjects")}</legend>
          <div className="flex flex-wrap gap-3">
            {["HOUSEHOLD", "HOUSE"].map((kind) => (
              <label key={kind} className="flex items-center gap-2 text-sm">
                <input type="checkbox" checked={subjects.includes(kind)} onChange={() => toggleSubject(kind)} />
                {t(`subjects.${kind}`)}
              </label>
            ))}
            {members.map((m) => (
              <label key={m.id} className="flex items-center gap-2 text-sm">
                <input type="checkbox" checked={subjects.includes(`MEMBER:${m.id}`)} onChange={() => toggleSubject(`MEMBER:${m.id}`)} />
                {m.displayName}
              </label>
            ))}
          </div>
          <p className="mt-1 text-xs text-stone-500">{t("subjects.hint")}</p>
        </fieldset>
        <ErrorBox message={uploadError} />
        {uploadMessage && <InfoBox>{uploadMessage}</InfoBox>}
        <div>
          <button type="submit" className="btn-primary" disabled={!file || uploading} data-testid="upload-submit">
            {uploading ? t("documents.uploading") : t("documents.upload")}
          </button>
        </div>
      </form>

      <form
        className="mb-4 flex gap-2"
        role="search"
        onSubmit={(e) => {
          e.preventDefault();
          setSubmittedQuery(query.trim());
        }}
      >
        <input
          type="search"
          className="field"
          placeholder={t("documents.searchPlaceholder")}
          aria-label={t("documents.search")}
          value={query}
          onChange={(e) => setQuery(e.target.value)}
          data-testid="search-input"
        />
        <button type="submit" className="btn-secondary">
          {t("documents.search")}
        </button>
      </form>
      <p className="mb-3 text-xs text-stone-500">{t("documents.searchHint")}</p>

      <ErrorBox message={error} />
      {!list && !error && <Loading />}
      {list && (
        <div className="flex flex-col gap-2">
          {list.contentSearch === "UNAVAILABLE" && (
            <p className="alert-warn" role="alert">
              {t("documents.contentSearchUnavailable")}
            </p>
          )}
          <p className="text-sm text-stone-500">{t("documents.count", { count: list.totalDocuments })}</p>
          {list.items.length === 0 && (
            <EmptyState>{submittedQuery ? t("documents.noResults", { query: submittedQuery }) : t("documents.empty")}</EmptyState>
          )}
          {list.items.map((document) => (
            <DocumentCard key={document.id} document={document} />
          ))}
        </div>
      )}
    </>
  );
}
