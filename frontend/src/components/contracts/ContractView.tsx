import { useCallback, useEffect, useState, type ReactNode } from "react";
import { Link } from "react-router";
import { useTranslation } from "react-i18next";
import { api, toApiError, unwrap } from "../../api/client";
import type { ContractDto, ContractFactDto } from "../../api/types";
import { useSession } from "../../auth/SessionContext";
import { CONTRACT_STATUSES, factSummary, groupFacts, isHistory } from "../../lib/contracts";
import { formatDateTime } from "../../lib/format";
import { problemMessage } from "../../lib/problem";
import { EmptyState, ErrorBox, InfoBox, Loading } from "../common";
import { ContractDeadlineCard } from "../ContractDeadlineCard";
import { ContractFactCard, type FactCorrection } from "../ContractFactCard";
import { ContractFactForm, type EnteredFact } from "../ContractFactForm";
import { MoveToTrashButton, TrashBanner, useTrashUndo } from "../TrashControls";
import { Checkbox } from "../ui";

type Result = { data?: ContractDto; error?: unknown; response: Response };

interface Props {
  id: string;
  /** The name as a page heading or as the heading of the context panel. */
  heading: (name: string) => ReactNode;
  /**
   * The context panel shows the deadline, the conflicts and the current statements with their decisions; entering
   * statements, the history and managing the contract stay on the contract page.
   */
  compact?: boolean;
  /** After every successful write, so a list next to the view can follow. */
  onChanged?: () => void;
}

/**
 * Reviewing one contract: the cancellation deadline with its derivation, the conflicts Aegis reports
 * instead of resolving, and every statement with its source document and page, "Confirmed" in the data colour and
 * "Suggestion" in the action colour. Each write carries the version the member saw, so a concurrent decision is
 * reported and reloaded instead of being overwritten.
 */
export function ContractView({ id, heading, compact = false, onChanged }: Props) {
  const { t, i18n } = useTranslation();
  const { memberName } = useSession();
  const [contract, setContract] = useState<ContractDto | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [stale, setStale] = useState(false);
  const [busy, setBusy] = useState(false);
  const [showHistory, setShowHistory] = useState(!compact);
  const [entering, setEntering] = useState(false);
  const [renaming, setRenaming] = useState(false);
  const [nameDraft, setNameDraft] = useState("");
  const [message, setMessage] = useState<string | null>(null);
  const { notify: notifyTrashed, dismiss: dismissTrashed, toast: trashToast } = useTrashUndo<ContractDto>();

  const load = useCallback(async () => {
    try {
      setContract(unwrap(await api.GET("/api/contracts/{id}", { params: { path: { id } } })));
      setError(null);
    } catch (failure) {
      setError(problemMessage(t, failure));
    }
  }, [id, t]);

  useEffect(() => {
    void load();
  }, [load]);

  /** Runs one version-checked write. A 409 means someone else decided first: reload and let the member re-judge. */
  const run = async (call: () => Promise<Result>, success?: string, after?: (changed: ContractDto) => void) => {
    setBusy(true);
    setError(null);
    setStale(false);
    setMessage(null);
    try {
      const changed = unwrap(await call());
      setContract(changed);
      if (success) setMessage(success);
      after?.(changed);
      onChanged?.();
    } catch (failure) {
      if (toApiError(failure).status === 409) {
        setStale(true);
        await load();
      } else {
        setError(problemMessage(t, failure));
      }
    } finally {
      setBusy(false);
    }
  };

  if (!contract) {
    return (
      <>
        <ErrorBox message={error} />
        {!error && <Loading />}
      </>
    );
  }

  const facts = groupFacts(contract.facts).flatMap((group) => group.facts);
  const visible = showHistory ? facts : facts.filter((fact) => !isHistory(fact));
  const hidden = contract.facts.filter(isHistory).length;
  const trashed = !!contract.trash;
  const path = { params: { path: { id: contract.id } } };
  /** Moving to the trash asks nothing and offers undo in a message. */
  const changeTrash = (action: "trash" | "restore", version: number) => {
    const options = { params: { path: { id } }, body: { expectedVersion: version } };
    if (action === "trash") {
      void run(() => api.POST("/api/contracts/{id}/trash", options), undefined, (changed) =>
        notifyTrashed(changed, () => changeTrash("restore", changed.version)),
      );
    } else {
      dismissTrashed();
      void run(() => api.POST("/api/contracts/{id}/restore", options), t("trash.restored"));
    }
  };
  const enterFact = (fact: EnteredFact) =>
    run(() => api.POST("/api/contracts/{id}/facts", { ...path, body: { ...fact, expectedVersion: contract.version } }));
  const correctFact = (fact: ContractFactDto, correction: FactCorrection) =>
    run(() =>
      api.POST("/api/contracts/{id}/facts/{factId}/correct", {
        params: { path: { id: contract.id, factId: fact.id } },
        body: { ...correction, expectedVersion: contract.version },
      }),
    );
  const decide = (fact: ContractFactDto, decision: "confirm" | "reject") =>
    void run(() =>
      api.POST(decision === "confirm" ? "/api/contracts/{id}/facts/{factId}/confirm" : "/api/contracts/{id}/facts/{factId}/reject", {
        params: { path: { id: contract.id, factId: fact.id } },
        body: { expectedVersion: contract.version },
      }),
    );

  return (
    <article className="flex flex-col gap-4" data-testid={compact ? "contract-panel" : "contract-view"} data-contract-id={contract.id}>
      <div className="flex flex-col gap-1.5">
        <p className="ui-eyebrow">
          {t("contracts.eyebrow")} · <b>{t(`contracts.status.${contract.status}`, { defaultValue: contract.status })}</b>
        </p>
        {heading(contract.name)}
        <p className="ui-meta">
          <span className="min-w-0 break-words">
            {contract.counterparty ? t("contracts.counterpartyLabel", { name: contract.counterparty }) : t("contracts.counterpartyUnknown")}
          </span>
          {!compact && (
            <span>
              {t("contracts.updatedAt", {
                date: formatDateTime(contract.updatedAt, i18n.language),
                name: memberName(contract.createdBy) ?? t("app.unknownMember"),
              })}
            </span>
          )}
        </p>
      </div>
      {compact && (
        <Link to={`/contracts/${contract.id}`} className="btn-secondary" data-testid="open-contract">
          {t("contracts.open")}
        </Link>
      )}

      {stale && (
        <p className="alert-warn" role="alert">
          {t("contracts.stale")}{" "}
          <button type="button" className="underline" onClick={() => void load()}>
            {t("contracts.reload")}
          </button>
        </p>
      )}
      <ErrorBox message={error} />
      {message && <InfoBox>{message}</InfoBox>}
      {contract.trash && <TrashBanner trash={contract.trash} busy={busy} onRestore={() => changeTrash("restore", contract.version)} />}

      <ContractDeadlineCard
        deadline={contract.deadline}
        facts={contract.facts}
        evaluatedOn={contract.evaluatedOn}
        active={contract.status === "ACTIVE" && !trashed}
      />

      {contract.conflicts.length > 0 && (
        <section className="flex flex-col gap-2" aria-labelledby={`conflicts-${contract.id}`}>
          <h2 id={`conflicts-${contract.id}`} className="ui-sec">
            <span>{t("contracts.conflicts.title")}</span>
            <span>{contract.conflicts.length}</span>
          </h2>
          <p className="text-[13px] leading-relaxed text-text-3">{t("contracts.conflicts.hint")}</p>
          {contract.conflicts.map((conflict) => {
            const established = contract.facts.find((fact) => fact.id === conflict.establishedFactId);
            const conflicting = conflict.conflictingFactIds
              .map((factId) => contract.facts.find((fact) => fact.id === factId))
              .filter((fact): fact is ContractFactDto => fact !== undefined);
            return (
              <div
                key={`${conflict.type}-${conflict.kind}-${conflict.conflictingFactIds.join("-")}`}
                className="alert-warn flex flex-col gap-1"
                role="note"
                data-testid="contract-conflict"
              >
                <p className="font-medium">
                  {t(`contracts.conflicts.kind.${conflict.kind}`, {
                    type: t(`contracts.factType.${conflict.type}`, { defaultValue: conflict.type }),
                  })}
                </p>
                {established && (
                  <p>
                    {t("contracts.conflicts.established")}{" "}
                    <a href={`#fact-${established.id}`} className="underline">
                      {factSummary(established, t, i18n.language)}
                    </a>
                  </p>
                )}
                {conflicting.length > 0 && (
                  <p>
                    {t("contracts.conflicts.against")}{" "}
                    {conflicting.map((fact, index) => (
                      <span key={fact.id}>
                        {index > 0 && ", "}
                        <a href={`#fact-${fact.id}`} className="underline">
                          {factSummary(fact, t, i18n.language)}
                        </a>
                      </span>
                    ))}
                  </p>
                )}
              </div>
            );
          })}
        </section>
      )}

      <section className="flex flex-col gap-2" aria-labelledby={`facts-${contract.id}`}>
        <h2 id={`facts-${contract.id}`} className="ui-sec">
          <span>{t("contracts.facts.title")}</span>
          <span>{visible.length}</span>
        </h2>
        {!compact && (hidden > 0 || !trashed) && (
          <div className="flex flex-wrap items-center justify-between gap-2">
            {hidden > 0 ? (
              <Checkbox checked={showHistory} onChange={(event) => setShowHistory(event.target.checked)}>
                <span className="text-sm">{t("contracts.facts.showHistory", { count: hidden })}</span>
              </Checkbox>
            ) : (
              <span />
            )}
            {!trashed && (
              <button
                type="button"
                className="btn-secondary"
                onClick={() => setEntering((open) => !open)}
                data-testid="new-fact-toggle"
                aria-expanded={entering}
              >
                {t("contracts.fact.new")}
              </button>
            )}
          </div>
        )}
        {entering && !trashed && !compact && (
          <ContractFactForm
            contract={contract}
            busy={busy}
            onCancel={() => setEntering(false)}
            onSubmit={(fact) => {
              setEntering(false);
              void enterFact(fact);
            }}
          />
        )}
        {visible.length === 0 && <EmptyState>{t("contracts.facts.empty")}</EmptyState>}
        <div className="flex flex-col gap-2">
          {visible.map((fact) => (
            <ContractFactCard
              key={fact.id}
              fact={fact}
              facts={contract.facts}
              basedOn={contract.deadline.basedOn}
              busy={busy || trashed}
              onConfirm={(target) => decide(target, "confirm")}
              onReject={(target) => decide(target, "reject")}
              onCorrect={(target, correction) => void correctFact(target, correction)}
            />
          ))}
        </div>
      </section>

      {!compact && !trashed && (
        <section className="mt-2 flex flex-col gap-3" aria-labelledby="contract-admin">
          <h2 id="contract-admin" className="ui-sec">
            <span>{t("contracts.manage.title")}</span>
          </h2>
          {renaming ? (
            <form
              className="card flex flex-col gap-2"
              aria-label={t("contracts.manage.renameLabel")}
              onSubmit={(event) => {
                event.preventDefault();
                setRenaming(false);
                void run(() => api.PUT("/api/contracts/{id}", { ...path, body: { name: nameDraft, expectedVersion: contract.version } }));
              }}
            >
              <label className="label" htmlFor="contract-name">
                {t("contracts.nameField")}
              </label>
              <input id="contract-name" type="text" className="field" value={nameDraft} onChange={(event) => setNameDraft(event.target.value)} />
              <div className="flex gap-2">
                <button type="submit" className="btn-secondary" disabled={busy}>
                  {t("app.save")}
                </button>
                <button type="button" className="btn-ghost" onClick={() => setRenaming(false)} disabled={busy}>
                  {t("app.cancel")}
                </button>
              </div>
            </form>
          ) : (
            <div className="flex flex-wrap items-end gap-2">
              <button
                type="button"
                className="btn-ghost"
                disabled={busy}
                onClick={() => {
                  setNameDraft(contract.name);
                  setRenaming(true);
                }}
              >
                {t("contracts.manage.rename")}
              </button>
              <label className="flex flex-col" htmlFor="contract-status">
                <span className="label">{t("contracts.manage.status")}</span>
                <select
                  id="contract-status"
                  className="field w-auto"
                  value={contract.status}
                  disabled={busy}
                  onChange={(event) =>
                    void run(() =>
                      api.POST("/api/contracts/{id}/status", { ...path, body: { status: event.target.value, expectedVersion: contract.version } }),
                    )
                  }
                >
                  {CONTRACT_STATUSES.map((status) => (
                    <option key={status} value={status}>
                      {t(`contracts.status.${status}`)}
                    </option>
                  ))}
                </select>
              </label>
            </div>
          )}
          <p className="text-[13px] leading-relaxed text-text-3">{t("contracts.manage.statusHint")}</p>
          <p className="text-[13px] leading-relaxed text-text-3">{t("trash.contractHint")}</p>
          <div>
            <MoveToTrashButton busy={busy} onTrash={() => changeTrash("trash", contract.version)} testId="trash-contract" />
          </div>
        </section>
      )}
      {trashToast}
    </article>
  );
}
