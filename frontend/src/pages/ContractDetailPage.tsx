import { useCallback, useEffect, useState } from "react";
import { Link, useParams } from "react-router";
import { useTranslation } from "react-i18next";
import { api, toApiError, unwrap } from "../api/client";
import type { ContractDto, ContractFactDto } from "../api/types";
import { useSession } from "../auth/SessionContext";
import { ContractDeadlineCard } from "../components/ContractDeadlineCard";
import { ContractFactCard, type FactCorrection } from "../components/ContractFactCard";
import { ContractFactForm, type EnteredFact } from "../components/ContractFactForm";
import { EmptyState, ErrorBox, Loading, PageTitle, Badge } from "../components/common";
import { CONTRACT_STATUSES, factSummary, groupFacts, isHistory } from "../lib/contracts";
import { formatDateTime } from "../lib/format";
import { problemMessage } from "../lib/problem";

type Result = { data?: ContractDto; error?: unknown; response: Response };

/**
 * Reviewing one contract: the deadline and what it rests on, the conflicts Aegis reports instead of resolving,
 * and every observation with its provenance and history. Each write carries the version the member saw, so a
 * concurrent decision is reported and reloaded instead of being overwritten.
 */
export function ContractDetailPage() {
  const { id = "" } = useParams();
  const { t, i18n } = useTranslation();
  const { memberName } = useSession();
  const [contract, setContract] = useState<ContractDto | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [stale, setStale] = useState(false);
  const [busy, setBusy] = useState(false);
  const [showHistory, setShowHistory] = useState(true);
  const [entering, setEntering] = useState(false);
  const [renaming, setRenaming] = useState(false);
  const [nameDraft, setNameDraft] = useState("");

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
  const run = async (call: () => Promise<Result>) => {
    setBusy(true);
    setError(null);
    setStale(false);
    try {
      setContract(unwrap(await call()));
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
        <BackLink />
        <PageTitle>{t("contracts.title")}</PageTitle>
        <ErrorBox message={error} />
        {!error && <Loading />}
      </>
    );
  }

  const groups = groupFacts(contract.facts);
  const visible = (facts: ContractFactDto[]) => (showHistory ? facts : facts.filter((fact) => !isHistory(fact)));
  const hidden = contract.facts.filter(isHistory).length;

  const enterFact = (fact: EnteredFact) =>
    run(() =>
      api.POST("/api/contracts/{id}/facts", {
        params: { path: { id: contract.id } },
        body: { ...fact, expectedVersion: contract.version },
      }),
    );

  const correctFact = (fact: ContractFactDto, correction: FactCorrection) =>
    run(() =>
      api.POST("/api/contracts/{id}/facts/{factId}/correct", {
        params: { path: { id: contract.id, factId: fact.id } },
        body: { ...correction, expectedVersion: contract.version },
      }),
    );

  return (
    <>
      <BackLink />
      <PageTitle
        actions={<Badge state={contract.status} label={t(`contracts.status.${contract.status}`)} />}
      >
        {contract.name}
      </PageTitle>

      <p className="mb-3 text-sm text-stone-600 dark:text-stone-400">
        {contract.counterparty
          ? t("contracts.counterpartyLabel", { name: contract.counterparty })
          : t("contracts.counterpartyUnknown")}
        {" · "}
        {t("contracts.updatedAt", {
          date: formatDateTime(contract.updatedAt, i18n.language),
          name: memberName(contract.createdBy) ?? t("app.unknownMember"),
        })}
      </p>

      {stale && (
        <p className="alert-warn" role="alert">
          {t("contracts.stale")}{" "}
          <button type="button" className="underline" onClick={() => void load()}>
            {t("contracts.reload")}
          </button>
        </p>
      )}
      <ErrorBox message={error} />

      <div className="mb-4">
        <ContractDeadlineCard
          deadline={contract.deadline}
          facts={contract.facts}
          evaluatedOn={contract.evaluatedOn}
        />
      </div>

      {contract.conflicts.length > 0 && (
        <section className="mb-4 flex flex-col gap-2" aria-labelledby="conflicts-heading">
          <h2 id="conflicts-heading" className="text-lg font-semibold">
            {t("contracts.conflicts.title")}
          </h2>
          <p className="text-sm text-stone-600 dark:text-stone-400">{t("contracts.conflicts.hint")}</p>
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

      <div className="mb-3 flex flex-wrap items-center justify-between gap-2">
        <h2 className="text-lg font-semibold">{t("contracts.facts.title")}</h2>
        <div className="flex flex-wrap items-center gap-3">
          {hidden > 0 && (
            <label className="flex items-center gap-2 text-sm">
              <input type="checkbox" checked={showHistory} onChange={(e) => setShowHistory(e.target.checked)} />
              {t("contracts.facts.showHistory", { count: hidden })}
            </label>
          )}
          <button
            type="button"
            className="btn-primary"
            onClick={() => setEntering((open) => !open)}
            data-testid="new-fact-toggle"
            aria-expanded={entering}
          >
            {t("contracts.fact.new")}
          </button>
        </div>
      </div>

      {entering && (
        <div className="mb-4">
          <ContractFactForm
            contract={contract}
            busy={busy}
            onCancel={() => setEntering(false)}
            onSubmit={(fact) => {
              setEntering(false);
              void enterFact(fact);
            }}
          />
        </div>
      )}

      {groups.length === 0 && <EmptyState>{t("contracts.facts.empty")}</EmptyState>}

      <div className="flex flex-col gap-4">
        {groups.map((group) => {
          const facts = visible(group.facts);
          if (facts.length === 0) return null;
          return (
            <section key={group.type} aria-labelledby={`facts-${group.type}`}>
              <h3 id={`facts-${group.type}`} className="mb-2 font-medium">
                {t(`contracts.factType.${group.type}`, { defaultValue: group.type })}
              </h3>
              <div className="flex flex-col gap-2">
                {facts.map((fact) => (
                  <ContractFactCard
                    key={fact.id}
                    fact={fact}
                    facts={contract.facts}
                    busy={busy}
                    onConfirm={(target) =>
                      void run(() =>
                        api.POST("/api/contracts/{id}/facts/{factId}/confirm", {
                          params: { path: { id: contract.id, factId: target.id } },
                          body: { expectedVersion: contract.version },
                        }),
                      )
                    }
                    onReject={(target) =>
                      void run(() =>
                        api.POST("/api/contracts/{id}/facts/{factId}/reject", {
                          params: { path: { id: contract.id, factId: target.id } },
                          body: { expectedVersion: contract.version },
                        }),
                      )
                    }
                    onCorrect={(target, correction) => void correctFact(target, correction)}
                  />
                ))}
              </div>
            </section>
          );
        })}
      </div>

      <section className="mt-6 flex flex-col gap-2" aria-labelledby="contract-admin">
        <h2 id="contract-admin" className="text-lg font-semibold">
          {t("contracts.manage.title")}
        </h2>
        {renaming ? (
          <form
            className="card flex flex-col gap-2"
            aria-label={t("contracts.manage.renameLabel")}
            onSubmit={(event) => {
              event.preventDefault();
              setRenaming(false);
              void run(() =>
                api.PUT("/api/contracts/{id}", {
                  params: { path: { id: contract.id } },
                  body: { name: nameDraft, expectedVersion: contract.version },
                }),
              );
            }}
          >
            <label className="label" htmlFor="contract-name">
              {t("contracts.nameField")}
            </label>
            <input
              id="contract-name"
              type="text"
              className="field"
              value={nameDraft}
              onChange={(e) => setNameDraft(e.target.value)}
            />
            <div className="flex gap-2">
              <button type="submit" className="btn-primary" disabled={busy}>
                {t("app.save")}
              </button>
              <button type="button" className="btn-secondary" onClick={() => setRenaming(false)} disabled={busy}>
                {t("app.cancel")}
              </button>
            </div>
          </form>
        ) : (
          <div className="flex flex-wrap items-center gap-2">
            <button
              type="button"
              className="btn-secondary"
              disabled={busy}
              onClick={() => {
                setNameDraft(contract.name);
                setRenaming(true);
              }}
            >
              {t("contracts.manage.rename")}
            </button>
            <label className="label mb-0" htmlFor="contract-status">
              {t("contracts.manage.status")}
            </label>
            <select
              id="contract-status"
              className="field w-auto"
              value={contract.status}
              disabled={busy}
              onChange={(e) =>
                void run(() =>
                  api.POST("/api/contracts/{id}/status", {
                    params: { path: { id: contract.id } },
                    body: { status: e.target.value, expectedVersion: contract.version },
                  }),
                )
              }
            >
              {CONTRACT_STATUSES.map((status) => (
                <option key={status} value={status}>
                  {t(`contracts.status.${status}`)}
                </option>
              ))}
            </select>
          </div>
        )}
        <p className="text-sm text-stone-600 dark:text-stone-400">{t("contracts.manage.statusHint")}</p>
      </section>
    </>
  );
}

function BackLink() {
  const { t } = useTranslation();
  return (
    <p className="mb-2 text-sm">
      <Link to="/contracts" className="text-sky-800 hover:underline dark:text-sky-300">
        ← {t("contracts.title")}
      </Link>
    </p>
  );
}
