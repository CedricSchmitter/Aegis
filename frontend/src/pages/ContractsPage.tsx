import { useCallback, useEffect, useState, type FormEvent } from "react";
import { Link, useNavigate } from "react-router";
import { useTranslation } from "react-i18next";
import { api, unwrap } from "../api/client";
import type { ContractSummaryDto } from "../api/types";
import { BigTitle, EmptyState, ErrorBox, Loading } from "../components/common";
import { ContractCard } from "../components/contracts/ContractCard";
import { ContractView } from "../components/contracts/ContractView";
import { ContextPanel, usePanelShown } from "../components/shell/ContextPanel";
import { Icon } from "../components/shell/icons";
import { useDesktop } from "../components/shell/ShellData";
import { todayIso } from "../lib/format";
import { problemMessage } from "../lib/problem";

/**
 * Every contract with its cancellation date and countdown, highlighted from 30 days before it. A contract
 * whose rules are incomplete says so instead of disappearing. From 1280 px a click selects a contract and shows its
 * deadline and statements in the context panel.
 */
export function ContractsPage() {
  const { t } = useTranslation();
  const navigate = useNavigate();
  const desktop = useDesktop();
  const panelShown = usePanelShown();
  const [contracts, setContracts] = useState<ContractSummaryDto[] | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [selectedId, setSelectedId] = useState<string | null>(null);
  const [creating, setCreating] = useState(false);
  const [busy, setBusy] = useState(false);
  const [name, setName] = useState("");
  const [counterparty, setCounterparty] = useState("");
  const [invalid, setInvalid] = useState<string | null>(null);

  const load = useCallback(async () => {
    try {
      setContracts(unwrap(await api.GET("/api/contracts")));
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
      const created = unwrap(await api.POST("/api/contracts", { body: { name: name.trim(), counterparty: counterparty.trim() || null } }));
      void navigate(`/contracts/${created.id}`);
    } catch (failure) {
      setError(problemMessage(t, failure));
    } finally {
      setBusy(false);
    }
  };

  const today = todayIso();
  const active = contracts?.filter((contract) => contract.status === "ACTIVE").length ?? 0;
  const selected = panelShown && contracts ? (contracts.find((contract) => contract.id === selectedId) ?? contracts[0] ?? null) : null;

  return (
    <>
      <BigTitle count={contracts && t("contracts.activeCount", { count: active })}>{t("contracts.title")}</BigTitle>
      <div className="flex flex-col gap-4">
        <ErrorBox message={error} />
        {!contracts && !error && <Loading />}
        {contracts && contracts.length === 0 && <EmptyState>{t("contracts.empty")}</EmptyState>}
        {contracts && contracts.length > 0 && (
          <ul className="flex flex-col gap-2" aria-label={t("contracts.title")}>
            {contracts.map((contract) => (
              <ContractCard
                key={contract.id}
                contract={contract}
                on={today}
                selected={selected?.id === contract.id}
                onSelect={panelShown ? setSelectedId : null}
              />
            ))}
          </ul>
        )}

        <button
          type="button"
          className="btn-secondary self-start"
          onClick={() => setCreating((open) => !open)}
          data-testid="new-contract-toggle"
          aria-expanded={creating}
        >
          <Icon name="plus" className="h-4 w-4" />
          {t("contracts.new")}
        </button>
        {creating && (
          <form className="card flex flex-col gap-3" onSubmit={create} aria-label={t("contracts.newLabel")}>
            <div>
              <label className="label" htmlFor="contract-new-name">
                {t("contracts.nameField")}
              </label>
              <input id="contract-new-name" type="text" className="field" value={name} onChange={(event) => setName(event.target.value)} />
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
                onChange={(event) => setCounterparty(event.target.value)}
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
              <button type="button" className="btn-ghost" onClick={() => setCreating(false)} disabled={busy}>
                {t("app.cancel")}
              </button>
            </div>
          </form>
        )}
        {!desktop && (
          <Link to="/trash" className="ui-endlink" data-testid="open-trash">
            <Icon name="trash" className="h-4 w-4" />
            {t("trash.open")}
          </Link>
        )}
      </div>
      {selected && (
        <ContextPanel>
          <ContractView
            key={selected.id}
            id={selected.id}
            compact
            onChanged={() => void load()}
            heading={(title) => <h2 className="text-lg leading-tight font-bold break-words">{title}</h2>}
          />
        </ContextPanel>
      )}
    </>
  );
}
