import { useTranslation } from "react-i18next";

export type CountdownTone = "attention" | "danger" | "neutral";

/** Overdue is danger; the last `soonDays` days before the deadline ask for action; anything later is neutral. */
export function countdownTone(days: number, soonDays = 7): CountdownTone {
  if (days < 0) return "danger";
  if (days <= soonDays) return "attention";
  return "neutral";
}

/** Days left until a deadline as a large mono number; overdue deadlines count the days since, in red. */
export function Countdown({ days, soonDays }: { days: number; soonDays?: number }) {
  const { t } = useTranslation();
  const overdue = days < 0;
  const count = Math.abs(days);
  const unit = overdue ? t("ui.countdown.overdue", { count }) : days === 0 ? t("ui.countdown.today") : t("ui.countdown.left", { count });
  return (
    <span className="ui-countdown" data-tone={countdownTone(days, soonDays)}>
      <b aria-hidden="true">{count}</b>
      <small aria-hidden="true">{unit}</small>
      <span className="sr-only">{days === 0 ? unit : `${count} ${unit}`}</span>
    </span>
  );
}
