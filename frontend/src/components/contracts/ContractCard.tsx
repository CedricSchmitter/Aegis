import { Link, useNavigate } from "react-router";
import { useTranslation } from "react-i18next";
import type { ContractDeadlineItemDto, ContractSummaryDto } from "../../api/types";
import { formatDate } from "../../lib/format";
import { DEADLINE_WINDOW_DAYS, daysUntil } from "../../lib/tasks";
import { Badge } from "../common";
import { isSelectClick } from "../tasks/TaskCard";
import { Countdown, cardClass } from "../ui";

/** A cancellation deadline on Today: the contract, the date and the days left in the action colour. */
export function DeadlineCard({ item, on }: { item: ContractDeadlineItemDto; on: string }) {
  const { t, i18n } = useTranslation();
  return (
    <li>
      <Link
        to={`/contracts/${item.contractId}`}
        className="card card-attention flex items-center gap-3.5"
        data-testid="deadline-card"
        data-contract-id={item.contractId}
      >
        <span className="flex min-w-0 flex-1 flex-col gap-1.5">
          <span className="text-[15px] leading-snug font-semibold break-words">{item.name}</span>
          <span className="ui-meta">
            <span className="text-text-2">{t("contracts.deadline.cancelBy", { date: formatDate(item.cancellationDeadline, i18n.language) })}</span>
          </span>
        </span>
        <Countdown days={daysUntil(item.cancellationDeadline, on)} soonDays={DEADLINE_WINDOW_DAYS} />
      </Link>
    </li>
  );
}

interface Props {
  contract: ContractSummaryDto;
  on: string;
  /** Shown in the context panel; a click then selects instead of opening the contract. */
  selected?: boolean;
  onSelect?: ((id: string) => void) | null;
}

/**
 * A contract in the list: the cancellation date with its countdown, highlighted from 30 days before it;
 * a passed deadline is red. The status appears only when the contract is no longer active.
 */
export function ContractCard({ contract, on, selected = false, onSelect = null }: Props) {
  const { t, i18n } = useTranslation();
  const navigate = useNavigate();
  const active = contract.status === "ACTIVE";
  const deadline = contract.deadline.determined ? contract.deadline.deadline : null;
  const days = deadline && active ? daysUntil(deadline, on) : null;
  const tone = days === null ? undefined : days < 0 ? "danger" : days <= DEADLINE_WINDOW_DAYS ? "attention" : undefined;

  return (
    <li>
      <Link
        to={`/contracts/${contract.id}`}
        className={cardClass(tone, selected, "flex items-center gap-3.5")}
        data-testid="contract-item"
        data-contract-id={contract.id}
        data-selected={onSelect ? selected : undefined}
        aria-current={selected && onSelect ? "true" : undefined}
        onClick={(event) => {
          if (onSelect && isSelectClick(event)) {
            event.preventDefault();
            onSelect(contract.id);
          }
        }}
        onDoubleClick={() => onSelect && navigate(`/contracts/${contract.id}`)}
      >
        <span className="flex min-w-0 flex-1 flex-col gap-1.5">
          <span className="text-[15px] leading-snug font-semibold break-words">{contract.name}</span>
          <span className="ui-meta">
            {contract.counterparty && <span className="min-w-0 break-words">{contract.counterparty}</span>}
            <span className="text-text-2">
              {deadline ? t("contracts.deadline.cancelBy", { date: formatDate(deadline, i18n.language) }) : t("contracts.deadline.none")}
            </span>
            {contract.pendingFacts > 0 && <span className="text-action">{t("contracts.pendingFacts", { count: contract.pendingFacts })}</span>}
            {contract.conflicts > 0 && <span className="text-action">{t("contracts.conflictCount", { count: contract.conflicts })}</span>}
          </span>
          {!active && (
            <span>
              <Badge state={contract.status} label={t(`contracts.status.${contract.status}`, { defaultValue: contract.status })} />
            </span>
          )}
        </span>
        {days !== null && <Countdown days={days} soonDays={DEADLINE_WINDOW_DAYS} />}
      </Link>
    </li>
  );
}
