import { useCallback, useEffect, useState, type FormEvent } from "react";
import { Link, useNavigate } from "react-router";
import { useTranslation } from "react-i18next";
import { api, unwrap } from "../api/client";
import type { ContractDeadlineItemDto, ContractSummaryDto } from "../api/types";
import { Badge, EmptyState, ErrorBox, Loading, PageTitle } from "../components/common";
import { formatDate } from "../lib/format";
import { problemMessage } from "../lib/problem";

/**
 * Every contract with the state of its cancellation deadline, and the deadlines the confirmed facts already
 * establish. A contract whose rules are incomplete says so here instead of disappearing from the overview.
 */
export function ContractsPage() {
  const { t, i18n } = useTranslation();
  const navigate = useNavigate();
  const [contracts, setContracts] = useState<ContractSummaryDto[] | null>(null);
  const [deadlines, setDeadlines] = useState<ContractDeadlineItemDto[]>([]);
  const [error, setError] = useState<string | null>(null);
  const [creating, setCreating] = useState(false);
  const [busy, setBusy] = useState(false);
  const [name, setName] = useState("");
  const [counterparty, setCounterparty] = useState("");
  const [invalid, setInvalid] = useState<string | null>(null);

  const load = useCallback(async () => {
    try {
      const [list, due] = await Promise.all([
        api.GET("/api/contracts"),
        api.GET("/api/contracts/deadlines"),
      ]);
      setContracts(unwrap(list));
      setDeadlines(unwrap(due));
      setError(null);
    } catch (failure) {
      setError(problemMessage(t, failure));
    }
  }, [t]);

  useEffect(() => {
    void load();
  }, [load]);

  const create = async (event: FormEvent) => {
    event.preventDefault();
    if (!name.trim()) {
      setInvalid(t("contracts.nameRequired"));
      return;
    }
    setInvalid(null);
    setBusy(true);
    try {
      const created = unwrap(
        await api.POST("/api/contracts", {
          body: { name: name.trim(), counterparty: counterparty.trim() || null },
        }),
      );
      void navigate(`/contracts/${created.id}`);
    } catch (failure) {
      setError(problemMessage(t, failure));
    } finally {
      setBusy(false);
    }
  };

  return (
    <>
      <PageTitle
        actions={
          <button
            type="button"
            className="btn-primary"
            onClick={() => setCreating((open) => !open)}
            data-testid="new-contract-toggle"
            aria-expanded={creating}
          >
            {t("contracts.new")}
          </button>
        }
      >
        {t("contracts.title")}
      </PageTitle>

      {creating && (
        <form className="card mb-4 flex flex-col gap-3" onSubmit={create} aria-label={t("contracts.newLabel")}>
          <div>
            <label className="label" htmlFor="contract-new-name">
              {t("contracts.nameField")}
            </label>
            <input
              id="contract-new-name"
              type="text"
              className="field"
              value={name}
              onChange={(e) => setName(e.target.value)}
            />
          </div>
          <div>
            <label className="label" htmlFor="contract-new-counterparty">
              {t("contracts.counterpartyField")}
            </label>
            <input
              id="contract-new-counterparty"
              type="text"
              className="field"
              value={counterparty}
              onChange={(e) => setCounterparty(e.target.value)}
            />
          </div>
          {invalid && (
            <p className="alert-error" role="alert">
              {invalid}
            </p>
          )}
          <div className="flex gap-2">
            <button type="submit" className="btn-primary" disabled={busy}>
              {t("contracts.create")}
            </button>
            <button type="button" className="btn-secondary" onClick={() => setCreating(false)} disabled={busy}>
              {t("app.cancel")}
            </button>
          </div>
        </form>
      )}

      <ErrorBox message={error} />

      {deadlines.length > 0 && (
        <section className="mb-4" aria-labelledby="upcoming-deadlines">
          <h2 id="upcoming-deadlines" className="mb-2 text-lg font-semibold">
            {t("contracts.deadline.upcoming")}
          </h2>
          <ul className="flex flex-col gap-2">
            {[...deadlines]
              .sort((a, b) => a.cancellationDeadline.localeCompare(b.cancellationDeadline))
              .map((item) => (
                <li key={item.contractId} className="card flex flex-wrap items-center justify-between gap-2">
                  <Link
                    to={`/contracts/${item.contractId}`}
                    className="font-medium text-sky-800 hover:underline dark:text-sky-300"
                  >
                    {item.name}
                  </Link>
                  <span>
                    {t("contracts.deadline.cancelBy", {
                      date: formatDate(item.cancellationDeadline, i18n.language),
                    })}
                  </span>
                </li>
              ))}
          </ul>
        </section>
      )}

      {!contracts && !error && <Loading />}
      {contracts && contracts.length === 0 && <EmptyState>{t("contracts.empty")}</EmptyState>}
      {contracts && contracts.length > 0 && (
        <div className="flex flex-col gap-2">
          {contracts.map((contract) => (
            <article key={contract.id} className="card flex flex-col gap-1" data-testid="contract-item">
              <div className="flex flex-wrap items-start justify-between gap-2">
                <h2 className="font-semibold">
                  <Link to={`/contracts/${contract.id}`} className="hover:underline">
                    {contract.name}
                  </Link>
                </h2>
                <Badge state={contract.status} label={t(`contracts.status.${contract.status}`)} />
              </div>
              <p className="text-sm text-stone-600 dark:text-stone-400">
                {contract.counterparty
                  ? t("contracts.counterpartyLabel", { name: contract.counterparty })
                  : t("contracts.counterpartyUnknown")}
              </p>
              <p className="text-sm">
                {contract.deadline.determined && contract.deadline.deadline
                  ? t("contracts.deadline.cancelBy", {
                      date: formatDate(contract.deadline.deadline, i18n.language),
                    })
                  : t("contracts.deadline.none")}
              </p>
              {(contract.pendingFacts > 0 || contract.conflicts > 0) && (
                <p className="text-sm">
                  {contract.pendingFacts > 0 && t("contracts.pendingFacts", { count: contract.pendingFacts })}
                  {contract.pendingFacts > 0 && contract.conflicts > 0 && " · "}
                  {contract.conflicts > 0 && t("contracts.conflictCount", { count: contract.conflicts })}
                </p>
              )}
            </article>
          ))}
        </div>
      )}
    </>
  );
}
