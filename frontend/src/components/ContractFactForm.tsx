import { useMemo, useState, type FormEvent } from "react";
import { useTranslation } from "react-i18next";
import { api, unwrap } from "../api/client";
import type { ContractDto, ContractSourceDto, DocumentListResponse } from "../api/types";
import { FACT_TYPES, VALUE_KIND_OF, type FactType } from "../lib/contracts";
import { problemMessage } from "../lib/problem";
import {
  EMPTY_VALUE_DRAFT,
  FactValueFields,
  ValidityFields,
  canonicalValue,
  type ValueDraft,
} from "./ContractFactFields";

/** What the member has to send for a fact; the page turns it into the request and owns the version check. */
export interface EnteredFact {
  type: FactType;
  value: string;
  validFrom: string | null;
  validUntil: string | null;
  sourceDocumentId: string | null;
  sourcePage: number | null;
  sourceQuote: string | null;
  note: string | null;
}

interface Props {
  contract: ContractDto;
  busy: boolean;
  onSubmit(fact: EnteredFact): void;
  onCancel(): void;
}

/**
 * Entering a fact a member read in a document. A page or a quote can only be given together with the document
 * they were read in, so Aegis never stores a page reference that no source supports.
 */
export function ContractFactForm({ contract, busy, onSubmit, onCancel }: Props) {
  const { t } = useTranslation();
  const [type, setType] = useState<FactType>("END_DATE");
  const [draft, setDraft] = useState<ValueDraft>(EMPTY_VALUE_DRAFT);
  const [validity, setValidity] = useState({ from: "", until: "" });
  const [documentId, setDocumentId] = useState("");
  const [page, setPage] = useState("");
  const [quote, setQuote] = useState("");
  const [note, setNote] = useState("");
  const [invalid, setInvalid] = useState<string | null>(null);

  const kind = VALUE_KIND_OF[type];
  const value = canonicalValue(kind, draft);

  const [found, setFound] = useState<ContractSourceDto[]>([]);
  const documents = useMemo(() => {
    const byId = new Map<string, ContractSourceDto>();
    for (const source of [...contract.sources, ...found]) {
      if (!byId.has(source.documentId)) byId.set(source.documentId, source);
    }
    return [...byId.values()];
  }, [contract.sources, found]);

  const submit = (event: FormEvent) => {
    event.preventDefault();
    if (!value) {
      setInvalid(t("contracts.fact.valueRequired"));
      return;
    }
    if (!documentId && (page.trim() || quote.trim())) {
      setInvalid(t("contracts.fact.sourceRequiredForPage"));
      return;
    }
    setInvalid(null);
    onSubmit({
      type,
      value,
      validFrom: validity.from || null,
      validUntil: validity.until || null,
      sourceDocumentId: documentId || null,
      sourcePage: page.trim() ? Number(page) : null,
      sourceQuote: quote.trim() || null,
      note: note.trim() || null,
    });
  };

  return (
    <form className="card flex flex-col gap-3" onSubmit={submit} aria-label={t("contracts.fact.newLabel")}>
      <div>
        <label className="label" htmlFor="new-fact-type">
          {t("contracts.fact.type")}
        </label>
        <select
          id="new-fact-type"
          className="field"
          value={type}
          onChange={(e) => {
            setType(e.target.value as FactType);
            setDraft(EMPTY_VALUE_DRAFT);
          }}
        >
          {FACT_TYPES.map((option) => (
            <option key={option} value={option}>
              {t(`contracts.factType.${option}`)}
            </option>
          ))}
        </select>
      </div>

      <FactValueFields kind={kind} draft={draft} onChange={setDraft} idPrefix="new-fact" />

      <ValidityFields from={validity.from} until={validity.until} onChange={setValidity} idPrefix="new-fact" />
      <p className="text-sm text-stone-600 dark:text-stone-400">{t("contracts.fact.validityHint")}</p>

      <SourcePicker
        documents={documents}
        value={documentId}
        onChange={(id) => {
          setDocumentId(id);
          if (!id) {
            setPage("");
            setQuote("");
          }
        }}
        onFound={setFound}
      />

      {documentId && (
        <>
          <div className="flex gap-2">
            <div className="w-32">
              <label className="label" htmlFor="new-fact-page">
                {t("contracts.fact.page")}
              </label>
              <input
                id="new-fact-page"
                type="number"
                inputMode="numeric"
                min={1}
                className="field"
                value={page}
                onChange={(e) => setPage(e.target.value)}
              />
            </div>
            <div className="flex-1">
              <label className="label" htmlFor="new-fact-quote">
                {t("contracts.fact.quote")}
              </label>
              <input
                id="new-fact-quote"
                type="text"
                className="field"
                value={quote}
                onChange={(e) => setQuote(e.target.value)}
              />
            </div>
          </div>
          <p className="text-sm text-stone-600 dark:text-stone-400">{t("contracts.fact.provenanceHint")}</p>
        </>
      )}

      <div>
        <label className="label" htmlFor="new-fact-note">
          {t("contracts.fact.note")}
        </label>
        <input
          id="new-fact-note"
          type="text"
          className="field"
          value={note}
          onChange={(e) => setNote(e.target.value)}
        />
      </div>

      {invalid && (
        <p className="alert-error" role="alert">
          {invalid}
        </p>
      )}
      <p className="text-sm text-stone-600 dark:text-stone-400">{t("contracts.fact.enterHint")}</p>
      <div className="flex gap-2">
        <button type="submit" className="btn-primary" disabled={busy}>
          {t("contracts.fact.enter")}
        </button>
        <button type="button" className="btn-secondary" onClick={onCancel} disabled={busy}>
          {t("app.cancel")}
        </button>
      </div>
    </form>
  );
}

/** Picks the document a fact was read in: the contract's known sources, or one found by title. */
function SourcePicker({
  documents,
  value,
  onChange,
  onFound,
}: {
  documents: ContractSourceDto[];
  value: string;
  onChange(documentId: string): void;
  onFound(sources: ContractSourceDto[]): void;
}) {
  const { t } = useTranslation();
  const [query, setQuery] = useState("");
  const [searching, setSearching] = useState(false);
  const [searchError, setSearchError] = useState<string | null>(null);
  const [noMatches, setNoMatches] = useState(false);

  const search = async () => {
    if (!query.trim()) return;
    setSearching(true);
    setSearchError(null);
    try {
      const result: DocumentListResponse = unwrap(
        await api.GET("/api/documents", { params: { query: { query: query.trim(), size: 10 } } }),
      );
      onFound(result.items.map((item) => ({ documentId: item.id, documentTitle: item.title })));
      setNoMatches(result.items.length === 0);
    } catch (failure) {
      setSearchError(problemMessage(t, failure));
    } finally {
      setSearching(false);
    }
  };

  return (
    <div className="flex flex-col gap-2">
      <div>
        <label className="label" htmlFor="new-fact-source">
          {t("contracts.fact.source")}
        </label>
        <select
          id="new-fact-source"
          className="field"
          value={value}
          onChange={(e) => onChange(e.target.value)}
        >
          <option value="">{t("contracts.fact.sourceNone")}</option>
          {documents.map((document) => (
            <option key={document.documentId} value={document.documentId}>
              {document.documentTitle ?? t("contracts.fact.sourceUnnamed")}
            </option>
          ))}
        </select>
      </div>
      <div className="flex items-end gap-2">
        <div className="flex-1">
          <label className="label" htmlFor="new-fact-source-search">
            {t("contracts.fact.sourceSearch")}
          </label>
          <input
            id="new-fact-source-search"
            type="search"
            className="field"
            value={query}
            onChange={(e) => {
              setQuery(e.target.value);
              setNoMatches(false);
            }}
            onKeyDown={(e) => {
              if (e.key === "Enter") {
                // The picker sits inside the fact form; searching must not submit the fact.
                e.preventDefault();
                void search();
              }
            }}
          />
        </div>
        <button type="button" className="btn-secondary" onClick={() => void search()} disabled={searching}>
          {t("documents.search")}
        </button>
      </div>
      {noMatches && <p className="text-sm text-stone-600 dark:text-stone-400">{t("contracts.fact.sourceNoMatches")}</p>}
      {searchError && (
        <p className="alert-error" role="alert">
          {searchError}
        </p>
      )}
    </div>
  );
}
