import { useTranslation } from "react-i18next";
import type { ContractDeadlineDto, ContractFactDto } from "../api/types";
import { formatDate } from "../lib/format";
import { factSummary } from "../lib/contracts";
import { DEADLINE_WINDOW_DAYS, daysUntil } from "../lib/tasks";
import { Countdown } from "./ui";

interface Props {
  deadline: ContractDeadlineDto;
  /** The observations the calculation rests on, so the basis can be named rather than merely asserted. */
  facts: ContractFactDto[];
  evaluatedOn: string;
  /** Only an active contract counts down; a cancelled or ended one keeps the date as information. */
  active: boolean;
}

/**
 * The contractual cancellation deadline with its derivation, or the reason there is none. Aegis calculates only from
 * confirmed rules and never estimates, so the second case names what is missing or contradictory instead of showing
 * an empty field. The date is the deadline itself; planned work and reminders belong to planning.
 */
export function ContractDeadlineCard({ deadline, facts, evaluatedOn, active }: Props) {
  const { t, i18n } = useTranslation();
  const basedOn = deadline.basedOn
    .map((id) => facts.find((fact) => fact.id === id))
    .filter((fact): fact is ContractFactDto => fact !== undefined);
  const hint = "text-[13px] leading-relaxed text-text-3";

  if (deadline.determined && deadline.deadline) {
    const days = active ? daysUntil(deadline.deadline, evaluatedOn) : null;
    const tone = days === null ? "" : days < 0 ? "card-danger" : "card-attention";
    return (
      <section className={`card flex flex-col gap-2.5 ${tone}`} aria-labelledby="deadline-heading" data-testid="contract-deadline">
        <h2 id="deadline-heading" className={`ui-eyebrow ${days !== null && days < 0 ? "text-danger" : days !== null ? "text-action" : ""}`}>
          {t("contracts.deadline.title")}
        </h2>
        <div className="flex items-center gap-3.5">
          <p className="min-w-0 flex-1 text-[22px] leading-tight font-semibold" data-testid="deadline-date">
            {formatDate(deadline.deadline, i18n.language)}
          </p>
          {days !== null && <Countdown days={days} soonDays={DEADLINE_WINDOW_DAYS} />}
        </div>
        {/* The rule is named, never implied: an unexpected basis says so rather than claiming the wrong one. */}
        <p className={hint}>
          {deadline.basis === "NOTICE_PERIOD_BEFORE_END" && deadline.contractEnd
            ? t("contracts.deadline.basis.NOTICE_PERIOD_BEFORE_END", {
                end: formatDate(deadline.contractEnd, i18n.language),
              })
            : t(`contracts.deadline.basis.${deadline.basis}`, { defaultValue: t("contracts.deadline.basisUnknown") })}{" "}
          {t("contracts.deadline.contractualHint")}
        </p>
        {basedOn.length > 0 && (
          <div className="text-sm">
            <p className="text-text-3">{t("contracts.deadline.basedOn")}</p>
            <ul className="mt-1 flex flex-col gap-0.5">
              {basedOn.map((fact) => (
                <li key={fact.id}>
                  <a href={`#fact-${fact.id}`} className="text-data hover:underline">
                    {factSummary(fact, t, i18n.language)}
                  </a>
                </li>
              ))}
            </ul>
          </div>
        )}
        <p className="font-mono text-xs text-text-4">
          {t("contracts.deadline.evaluatedOn", { date: formatDate(evaluatedOn, i18n.language) })}
        </p>
      </section>
    );
  }

  return (
    <section className="card flex flex-col gap-2" aria-labelledby="deadline-heading" data-testid="contract-deadline">
      <h2 id="deadline-heading" className="ui-eyebrow">
        {t("contracts.deadline.title")}
      </h2>
      <p className="text-[15px] leading-snug font-semibold" data-testid="deadline-insufficient">
        {t("contracts.deadline.insufficient")}
      </p>
      {deadline.missing.length > 0 && (
        <p className="text-sm text-text-2">
          {t("contracts.deadline.missing", {
            types: deadline.missing.map((type) => t(`contracts.factType.${type}`, { defaultValue: type })).join(", "),
          })}
        </p>
      )}
      {deadline.contradicting.length > 0 && (
        <p className="text-sm text-text-2">
          {t("contracts.deadline.contradicting", {
            types: deadline.contradicting
              .map((type) => t(`contracts.factType.${type}`, { defaultValue: type }))
              .join(", "),
          })}
        </p>
      )}
      {deadline.missing.length === 0 && deadline.contradicting.length === 0 && (
        <p className="text-sm text-text-2">{t("contracts.deadline.insufficientUnspecified")}</p>
      )}
      <p className={hint}>{t("contracts.deadline.insufficientHint")}</p>
      <p className="font-mono text-xs text-text-4">
        {t("contracts.deadline.evaluatedOn", { date: formatDate(evaluatedOn, i18n.language) })}
      </p>
    </section>
  );
}
