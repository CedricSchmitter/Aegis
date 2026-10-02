import { useId } from "react";
import { Link } from "react-router";
import { useTranslation } from "react-i18next";
import type { HealthCheck } from "../../api/types";
import { areaLabel, checkText, lastOkText, problemCount, stateLabel, whatNowText } from "../../lib/status";
import { StateWord } from "../common";
import { useShellData } from "../shell/ShellData";
import { cardClass } from "../ui";

/**
 * The answer to "Ist alles in Ordnung?": a head card, then one row per area. A broken area is red
 * with its reason; "Nicht geprüft" stays grey and neutral. `compact` drops the test ids and the "what now?" card for
 * the context panel of other pages.
 */
export function StatusOverview({ checks, compact = false }: { checks: HealthCheck[]; compact?: boolean }) {
  const { t, i18n } = useTranslation();
  const headingId = useId();
  const problems = checks.filter((check) => check.state === "PROBLEM");
  const broken = problemCount(checks) > 0;
  return (
    <section aria-labelledby={headingId} className="flex flex-col gap-3" data-testid={compact ? undefined : "status-overview"}>
      <div className={cardClass(broken ? "danger" : "data", false, "flex flex-col gap-1.5")}>
        <h2 id={headingId} className={`ui-eyebrow ${broken ? "text-danger" : "text-data"}`}>
          {t("status.overview")}
        </h2>
        <p className={`${compact ? "text-base" : "text-xl"} leading-tight font-bold`} data-testid={compact ? undefined : "overview-summary"}>
          {broken ? t("status.overviewProblems", { count: problems.length }) : t("status.overviewOk")}
        </p>
      </div>
      <ul className="border-t border-line">
        {checks.map((check) => {
          const lastOk = lastOkText(t, check, i18n.language);
          return (
            <li
              key={check.area}
              className="ui-row"
              data-tone={check.state === "PROBLEM" ? "danger" : undefined}
              data-testid={compact ? undefined : `check-${check.area}`}
            >
              <span className="ui-row-text">
                <b>{areaLabel(t, check.area)}</b>
                <span>{checkText(t, check, i18n.language)}</span>
                {lastOk && <small className="font-mono text-xs text-text-4">{lastOk}</small>}
              </span>
              <StateWord state={`CHECK_${check.state}`} label={stateLabel(t, check.state)} />
            </li>
          );
        })}
      </ul>
      {!compact && problems.length > 0 && (
        <div className="card card-danger flex flex-col gap-2" data-testid="what-now">
          <h3 className="ui-eyebrow text-danger">{t("status.whatNow.title")}</h3>
          {problems.map((check) => (
            <p key={check.area} className="text-sm leading-relaxed text-text-2">
              {problems.length > 1 && <b className="font-semibold text-text-1">{areaLabel(t, check.area)}: </b>}
              {whatNowText(t, check)}
            </p>
          ))}
        </div>
      )}
    </section>
  );
}

/** The status overview in the context panel of Settings and Trash, from the shell's once-a-minute check. */
export function StatusPanel() {
  const { t } = useTranslation();
  const { checks } = useShellData();
  return (
    <div className="flex flex-col gap-3" data-testid="status-panel">
      <p className="ui-sec">
        <span>{t("status.title")}</span>
      </p>
      {checks ? <StatusOverview checks={checks} compact /> : <p className="text-sm text-text-3">{t("shell.status.unknown")}</p>}
      <Link to="/status" className="btn-secondary self-start">
        {t("status.open")}
      </Link>
    </div>
  );
}
