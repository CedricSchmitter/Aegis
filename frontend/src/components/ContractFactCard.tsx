import { useState, type ReactNode } from "react";
import { Link } from "react-router";
import { useTranslation } from "react-i18next";
import type { ContractFactDto } from "../api/types";
import { useSession } from "../auth/SessionContext";
import { formatDateTime } from "../lib/format";
import {
  VALUE_KIND_OF,
  factSummary,
  formatFactValue,
  isHistory,
  validityLabel,
  type FactType,
  type ValueKind,
} from "../lib/contracts";
import { Badge } from "./common";
import {
  FactValueFields,
  ValidityFields,
  canonicalValue,
  draftFromValue,
  type ValueDraft,
} from "./ContractFactFields";

/** What a correction sends: a new confirmed value that keeps the corrected observation as readable history. */
export interface FactCorrection {
  value: string;
  validFrom: string | null;
  validUntil: string | null;
  note: string | null;
}

interface Props {
  fact: ContractFactDto;
  /** All observations of the contract, so a correction can name the fact it replaces. */
  facts: ContractFactDto[];
  /** Fact ids the calculated deadline rests on, as the backend resolved them for the evaluation day. */
  basedOn: string[];
  busy: boolean;
  onConfirm(fact: ContractFactDto): void;
  onReject(fact: ContractFactDto): void;
  onCorrect(fact: ContractFactDto, correction: FactCorrection): void;
}

/**
 * One observation with everything the household needs to judge it: the value, how long it describes the contract,
 * who said it and where it was read. Provenance is shown exactly as far as it was stored — a page appears only
 * when the source really states one.
 */
export function ContractFactCard({ fact, facts, basedOn, busy, onConfirm, onReject, onCorrect }: Props) {
  const { t, i18n } = useTranslation();
  const { memberName } = useSession();
  const [correcting, setCorrecting] = useState(false);

  const kind = (VALUE_KIND_OF[fact.type as FactType] ?? fact.valueKind) as ValueKind;
  const history = isHistory(fact);
  const superseded = facts.find((other) => other.id === fact.supersededBy);
  const corrected = facts.find((other) => other.id === fact.corrects);

  const typeLabel = t(`contracts.factType.${fact.type}`, { defaultValue: fact.type });
  const suggestion = fact.reviewState === "EXTRACTED";

  return (
    <article
      id={`fact-${fact.id}`}
      data-testid="contract-fact"
      data-fact-id={fact.id}
      data-review-state={fact.reviewState}
      className={`card flex flex-col gap-2 ${suggestion ? "card-attention" : ""}`}
    >
      <div className="flex items-start gap-2.5">
        <h3 className="min-w-0 flex-1 text-[15px] leading-snug font-semibold break-words">{typeLabel}</h3>
        <Badge state={fact.reviewState} label={t(`contracts.reviewState.${fact.reviewState}`)} />
      </div>
      <p className={`font-mono text-[15px] break-words ${history ? "text-text-3" : "text-text-1"}`}>
        {formatFactValue(fact.valueKind, fact.value, t, i18n.language)}
      </p>
      <p className="ui-meta">
        <span>{validityLabel(fact, t, i18n.language)}</span>
        <span className="min-w-0 break-words">
          {t("contracts.fact.sourceLabel")}{" "}
          {fact.source ? (
            <>
              {/* A source deleted permanently (ADR-0021) keeps its page and quote here but has no page to open. */}
              {fact.source.documentDeleted ? (
                <span className="text-text-3" data-testid="fact-source-deleted">{t("trash.sourceDeleted")}</span>
              ) : (
                <Link to={`/documents/${fact.source.documentId}`} className="text-data hover:underline">
                  {fact.source.documentTitle ?? t("contracts.fact.sourceUnnamed")}
                </Link>
              )}
              {/* A page is shown only when the backend stored one; Aegis never invents a page reference. */}
              {fact.source.page !== null && fact.source.page !== undefined && (
                <>, {t("contracts.fact.pageLabel", { page: fact.source.page })}</>
              )}
              {!fact.source.documentTitle && !fact.source.documentDeleted && <> {t("contracts.fact.sourceGone")}</>}
              {fact.source.documentTrashed && <> {t("trash.sourceInTrash")}</>}
            </>
          ) : (
            t("contracts.fact.sourceMissing")
          )}
        </span>
        {/* Which statements hold on a given day is the backend's resolution, never one the UI re-derives. */}
        {basedOn.includes(fact.id) && <span className="text-data">{t("contracts.fact.usedForDeadline")}</span>}
      </p>

      {fact.source?.quote && (
        <blockquote className="border-l-2 border-line-strong pl-3 text-sm text-text-2 italic">{fact.source.quote}</blockquote>
      )}

      <dl className="flex flex-col gap-1 border-t border-line pt-2 text-[13px]">
        <Row label={t("contracts.fact.observedLabel")}>
          {fact.origin === "MANUAL"
            ? t("contracts.fact.observedManual", {
                name: memberName(fact.observedBy) ?? t("app.unknownMember"),
                date: formatDateTime(fact.observedAt, i18n.language),
              })
            : t("contracts.fact.observedAutomatic", { date: formatDateTime(fact.observedAt, i18n.language) })}
          {fact.confidence !== null && fact.confidence !== undefined && (
            <> {t("contracts.fact.confidence", { percent: Math.round(fact.confidence * 100) })}</>
          )}
          {fact.runRef && <> {t("contracts.fact.runRef", { run: fact.runRef })}</>}
        </Row>
        {fact.decidedAt && (
          <Row label={t("contracts.fact.decidedLabel")}>
            {t(`contracts.fact.decided.${fact.reviewState}`, {
              name: memberName(fact.decidedBy) ?? t("app.unknownMember"),
              date: formatDateTime(fact.decidedAt, i18n.language),
            })}
          </Row>
        )}
        {corrected && (
          <Row label={t("contracts.fact.correctsLabel")}>
            <a href={`#fact-${corrected.id}`} className="text-data hover:underline">
              {factSummary(corrected, t, i18n.language)}
            </a>
          </Row>
        )}
        {fact.supersededBy && (
          <Row label={t("contracts.fact.supersededByLabel")}>
            {superseded ? (
              <a href={`#fact-${superseded.id}`} className="text-data hover:underline">
                {factSummary(superseded, t, i18n.language)}
              </a>
            ) : (
              t("contracts.fact.supersededByUnknown")
            )}
          </Row>
        )}
        {fact.note && <Row label={t("contracts.fact.noteLabel")}>{fact.note}</Row>}
      </dl>

      {fact.reviewState === "SUPERSEDED" ? (
        <p className="text-[13px] text-text-3">{t("contracts.fact.supersededHint")}</p>
      ) : (
        <div className="flex flex-wrap gap-2">
          {(fact.reviewState === "EXTRACTED" || fact.reviewState === "REJECTED") && (
            <button
              type="button"
              className={suggestion ? "btn-primary" : "btn-secondary"}
              disabled={busy}
              onClick={() => onConfirm(fact)}
              data-testid="fact-confirm"
            >
              {t("contracts.fact.confirm")}
            </button>
          )}
          <button
            type="button"
            className="btn-ghost"
            disabled={busy}
            onClick={() => setCorrecting((open) => !open)}
            aria-expanded={correcting}
          >
            {t("contracts.fact.correct")}
          </button>
          {(fact.reviewState === "EXTRACTED" || fact.reviewState === "CONFIRMED") && (
            <button type="button" className="btn-ghost" disabled={busy} onClick={() => onReject(fact)} data-testid="fact-reject">
              {t("contracts.fact.reject")}
            </button>
          )}
        </div>
      )}

      {correcting && (
        <CorrectionForm
          fact={fact}
          kind={kind}
          busy={busy}
          onCancel={() => setCorrecting(false)}
          onSubmit={(correction) => {
            setCorrecting(false);
            onCorrect(fact, correction);
          }}
        />
      )}
    </article>
  );
}

function Row({ label, children }: { label: string; children: ReactNode }) {
  return (
    <div className="flex flex-wrap gap-x-2">
      <dt className="text-text-4">{label}</dt>
      <dd className="text-text-3">{children}</dd>
    </div>
  );
}

/** Correcting states a new value; the old observation stays readable, so a member can see what they decided on. */
function CorrectionForm({
  fact,
  kind,
  busy,
  onSubmit,
  onCancel,
}: {
  fact: ContractFactDto;
  kind: ValueKind;
  busy: boolean;
  onSubmit(correction: FactCorrection): void;
  onCancel(): void;
}) {
  const { t } = useTranslation();
  const [draft, setDraft] = useState<ValueDraft>(() => draftFromValue(kind, fact.value));
  const [validity, setValidity] = useState({ from: fact.validFrom ?? "", until: fact.validUntil ?? "" });
  const [note, setNote] = useState(fact.note);
  const [invalid, setInvalid] = useState<string | null>(null);

  return (
    <form
      className="flex flex-col gap-3 border border-line p-3"
      aria-label={t("contracts.fact.correctLabel")}
      onSubmit={(event) => {
        event.preventDefault();
        const value = canonicalValue(kind, draft);
        if (!value) {
          setInvalid(t("contracts.fact.valueRequired"));
          return;
        }
        setInvalid(null);
        onSubmit({
          value,
          validFrom: validity.from || null,
          validUntil: validity.until || null,
          note: note.trim() || null,
        });
      }}
    >
      <FactValueFields kind={kind} draft={draft} onChange={setDraft} idPrefix={`correct-${fact.id}`} />
      <ValidityFields
        from={validity.from}
        until={validity.until}
        onChange={setValidity}
        idPrefix={`correct-${fact.id}`}
      />
      <div>
        <label className="label" htmlFor={`correct-${fact.id}-note`}>
          {t("contracts.fact.note")}
        </label>
        <input
          id={`correct-${fact.id}-note`}
          type="text"
          className="field"
          value={note}
          onChange={(e) => setNote(e.target.value)}
        />
      </div>
      <p className="text-sm text-text-3">{t("contracts.fact.correctHint")}</p>
      {invalid && (
        <p className="alert-error" role="alert">
          {invalid}
        </p>
      )}
      <div className="flex gap-2">
        <button type="submit" className="btn-secondary" disabled={busy}>
          {t("contracts.fact.saveCorrection")}
        </button>
        <button type="button" className="btn-secondary" disabled={busy} onClick={onCancel}>
          {t("app.cancel")}
        </button>
      </div>
    </form>
  );
}
