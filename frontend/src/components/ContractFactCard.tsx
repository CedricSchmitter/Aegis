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

  return (
    <article
      id={`fact-${fact.id}`}
      data-testid="contract-fact"
      data-fact-id={fact.id}
      data-review-state={fact.reviewState}
      className={`card flex flex-col gap-2 ${history ? "opacity-75" : ""}`}
    >
      <div className="flex flex-wrap items-start justify-between gap-2">
        <div>
          <p className="text-lg font-semibold">{formatFactValue(fact.valueKind, fact.value, t, i18n.language)}</p>
          <p className="text-sm text-stone-600 dark:text-stone-400">{validityLabel(fact, t, i18n.language)}</p>
        </div>
        <div className="flex flex-col items-end gap-1">
          <Badge state={fact.reviewState} label={t(`contracts.reviewState.${fact.reviewState}`)} />
          {/* Which statements hold on a given day is the backend's resolution, never one the UI re-derives. */}
          {basedOn.includes(fact.id) && (
            <span className="text-xs font-medium text-emerald-800 dark:text-emerald-300">
              {t("contracts.fact.usedForDeadline")}
            </span>
          )}
        </div>
      </div>

      <dl className="flex flex-col gap-1 border-t border-stone-200 pt-2 text-sm dark:border-stone-800">
        <Row label={t("contracts.fact.observedLabel")}>
          {fact.origin === "MANUAL"
            ? t("contracts.fact.observedManual", {
                name: memberName(fact.observedBy) ?? t("app.unknownMember"),
                date: formatDateTime(fact.observedAt, i18n.language),
              })
            : t("contracts.fact.observedAutomatic", { date: formatDateTime(fact.observedAt, i18n.language) })}
          {fact.confidence !== null && fact.confidence !== undefined && (
            <>
              {" "}
              {t("contracts.fact.confidence", { percent: Math.round(fact.confidence * 100) })}
            </>
          )}
          {fact.runRef && <> {t("contracts.fact.runRef", { run: fact.runRef })}</>}
        </Row>

        <Row label={t("contracts.fact.sourceLabel")}>
          {fact.source ? (
            <>
              <Link
                to={`/documents/${fact.source.documentId}`}
                className="text-sky-800 hover:underline dark:text-sky-300"
              >
                {fact.source.documentTitle ?? t("contracts.fact.sourceUnnamed")}
              </Link>
              {/* A page is shown only when the backend stored one; Aegis never invents a page reference. */}
              {fact.source.page !== null && fact.source.page !== undefined && (
                <> · {t("contracts.fact.pageLabel", { page: fact.source.page })}</>
              )}
              {!fact.source.documentTitle && (
                <span className="text-stone-600 dark:text-stone-400"> {t("contracts.fact.sourceGone")}</span>
              )}
            </>
          ) : (
            <span className="text-stone-600 dark:text-stone-400">{t("contracts.fact.sourceMissing")}</span>
          )}
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
            <a href={`#fact-${corrected.id}`} className="text-sky-800 hover:underline dark:text-sky-300">
              {factSummary(corrected, t, i18n.language)}
            </a>
          </Row>
        )}
        {fact.supersededBy && (
          <Row label={t("contracts.fact.supersededByLabel")}>
            {superseded ? (
              <a href={`#fact-${superseded.id}`} className="text-sky-800 hover:underline dark:text-sky-300">
                {factSummary(superseded, t, i18n.language)}
              </a>
            ) : (
              t("contracts.fact.supersededByUnknown")
            )}
          </Row>
        )}
        {fact.note && <Row label={t("contracts.fact.noteLabel")}>{fact.note}</Row>}
      </dl>

      {fact.source?.quote && (
        <blockquote className="border-l-4 border-stone-300 pl-3 text-sm italic text-stone-700 dark:border-stone-700 dark:text-stone-300">
          {fact.source.quote}
        </blockquote>
      )}

      {fact.reviewState === "SUPERSEDED" ? (
        <p className="text-sm text-stone-600 dark:text-stone-400">{t("contracts.fact.supersededHint")}</p>
      ) : (
        <div className="flex flex-wrap gap-2">
          {(fact.reviewState === "EXTRACTED" || fact.reviewState === "REJECTED") && (
            <button type="button" className="btn-primary" disabled={busy} onClick={() => onConfirm(fact)}>
              {t("contracts.fact.confirm")}
            </button>
          )}
          {(fact.reviewState === "EXTRACTED" || fact.reviewState === "CONFIRMED") && (
            <button type="button" className="btn-secondary" disabled={busy} onClick={() => onReject(fact)}>
              {t("contracts.fact.reject")}
            </button>
          )}
          <button
            type="button"
            className="btn-secondary"
            disabled={busy}
            onClick={() => setCorrecting((open) => !open)}
            aria-expanded={correcting}
          >
            {t("contracts.fact.correct")}
          </button>
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
      <dt className="text-stone-600 dark:text-stone-400">{label}</dt>
      <dd>{children}</dd>
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
      className="flex flex-col gap-3 rounded-lg border border-stone-200 p-3 dark:border-stone-800"
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
      <p className="text-sm text-stone-600 dark:text-stone-400">{t("contracts.fact.correctHint")}</p>
      {invalid && (
        <p className="alert-error" role="alert">
          {invalid}
        </p>
      )}
      <div className="flex gap-2">
        <button type="submit" className="btn-primary" disabled={busy}>
          {t("contracts.fact.saveCorrection")}
        </button>
        <button type="button" className="btn-secondary" disabled={busy} onClick={onCancel}>
          {t("app.cancel")}
        </button>
      </div>
    </form>
  );
}
