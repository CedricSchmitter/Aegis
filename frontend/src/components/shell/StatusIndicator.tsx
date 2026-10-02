import { Link } from "react-router";
import { useTranslation } from "react-i18next";
import { statusDetail, statusHeadline, type StatusTone } from "../../lib/shellStatus";
import { Diamond } from "./icons";
import { useShellData } from "./ShellData";

/** Red only when something is broken (ADR-0019): the data colour when everything works, grey while unknown. */
export const TONE_TEXT: Record<StatusTone, string> = {
  ok: "text-data",
  problem: "text-danger",
  unknown: "text-text-4",
};

/** The status in the desktop header; it opens the status page. */
export function StatusIndicator() {
  const { t } = useTranslation();
  const { status } = useShellData();
  const headline = statusHeadline(t, status);
  return (
    <Link
      to="/status"
      className={`flex min-h-11 items-center gap-2 px-1 font-mono text-[11px] tracking-[0.18em] uppercase ${TONE_TEXT[status.tone]}`}
      aria-label={t("shell.status.label", { state: headline })}
      data-testid="status-indicator"
      data-tone={status.tone}
    >
      <Diamond />
      <span>{headline}</span>
    </Link>
  );
}

/** The status line on Today, which took the place of the Status tab. */
export function StatusLine() {
  const { t, i18n } = useTranslation();
  const { status } = useShellData();
  const detail = statusDetail(t, status, i18n.language);
  const problem = status.tone === "problem";
  return (
    <Link
      to="/status"
      className={`flex min-h-11 w-full items-center gap-2.5 border px-3 font-mono text-xs tracking-[0.12em] uppercase ${
        problem ? "border-danger bg-danger/7 text-danger" : `border-line bg-surface ${TONE_TEXT[status.tone]}`
      }`}
      data-testid="status-line"
      data-tone={status.tone}
    >
      <Diamond />
      <span>{statusHeadline(t, status)}</span>
      {detail && <span className={`ml-auto truncate ${problem ? "" : "text-text-4"}`}>{detail}</span>}
    </Link>
  );
}
