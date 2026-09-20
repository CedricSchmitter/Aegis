import { useTranslation } from "react-i18next";
import type { ContractDeadlineDto, ContractFactDto } from "../api/types";
import { formatDate } from "../lib/format";
import { factSummary } from "../lib/contracts";

interface Props {
  deadline: ContractDeadlineDto;
  /** The observations the calculation rests on, so the basis can be named rather than merely asserted. */
  facts: ContractFactDto[];
  evaluatedOn: string;
}

/**
 * The contractual cancellation deadline, or the reason there is none. Aegis calculates only from confirmed rules
 * and never estimates, so the second case names what is missing or contradictory instead of showing an empty
 * field. The date is the deadline itself; planned work and reminders belong to planning.
 */
export function ContractDeadlineCard({ deadline, facts, evaluatedOn }: Props) {
  const { t, i18n } = useTranslation();
  const basedOn = deadline.basedOn
    .map((id) => facts.find((fact) => fact.id === id))
    .filter((fact): fact is ContractFactDto => fact !== undefined);

  if (deadline.determined && deadline.deadline) {
    return (
      <section className="card flex flex-col gap-2" aria-labelledby="deadline-heading" data-testid="contract-deadline">
        <h2 id="deadline-heading" className="text-sm font-medium text-stone-600 dark:text-stone-400">
          {t("contracts.deadline.title")}
        </h2>
        <p className="text-2xl font-semibold" data-testid="deadline-date">
          {formatDate(deadline.deadline, i18n.language)}
        </p>
        {/* The rule is named, never implied: an unexpected basis says so rather than claiming the wrong one. */}
        <p>
          {deadline.basis === "NOTICE_PERIOD_BEFORE_END" && deadline.contractEnd
            ? t("contracts.deadline.basis.NOTICE_PERIOD_BEFORE_END", {
                end: formatDate(deadline.contractEnd, i18n.language),
              })
            : t(`contracts.deadline.basis.${deadline.basis}`, { defaultValue: t("contracts.deadline.basisUnknown") })}
        </p>
        {basedOn.length > 0 && (
          <div className="text-sm">
            <p className="text-stone-600 dark:text-stone-400">{t("contracts.deadline.basedOn")}</p>
            <ul className="list-inside list-disc">
              {basedOn.map((fact) => (
                <li key={fact.id}>
                  <a href={`#fact-${fact.id}`} className="text-sky-800 hover:underline dark:text-sky-300">
                    {factSummary(fact, t, i18n.language)}
                  </a>
                </li>
              ))}
            </ul>
          </div>
        )}
        <p className="text-sm text-stone-600 dark:text-stone-400">{t("contracts.deadline.contractualHint")}</p>
        <p className="text-sm text-stone-600 dark:text-stone-400">
          {t("contracts.deadline.evaluatedOn", { date: formatDate(evaluatedOn, i18n.language) })}
        </p>
      </section>
    );
  }

  return (
    <section className="card flex flex-col gap-2" aria-labelledby="deadline-heading" data-testid="contract-deadline">
      <h2 id="deadline-heading" className="text-sm font-medium text-stone-600 dark:text-stone-400">
        {t("contracts.deadline.title")}
      </h2>
      <p className="text-lg font-semibold" data-testid="deadline-insufficient">
        {t("contracts.deadline.insufficient")}
      </p>
      {deadline.missing.length > 0 && (
        <p>
          {t("contracts.deadline.missing", {
            types: deadline.missing.map((type) => t(`contracts.factType.${type}`, { defaultValue: type })).join(", "),
          })}
        </p>
      )}
      {deadline.contradicting.length > 0 && (
        <p>
          {t("contracts.deadline.contradicting", {
            types: deadline.contradicting
              .map((type) => t(`contracts.factType.${type}`, { defaultValue: type }))
              .join(", "),
          })}
        </p>
      )}
      {deadline.missing.length === 0 && deadline.contradicting.length === 0 && (
        <p>{t("contracts.deadline.insufficientUnspecified")}</p>
      )}
      <p className="text-sm text-stone-600 dark:text-stone-400">{t("contracts.deadline.insufficientHint")}</p>
      <p className="text-sm text-stone-600 dark:text-stone-400">
        {t("contracts.deadline.evaluatedOn", { date: formatDate(evaluatedOn, i18n.language) })}
      </p>
    </section>
  );
}
