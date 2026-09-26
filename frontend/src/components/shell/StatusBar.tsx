import { useTranslation } from "react-i18next";
import { formatDateTime } from "../../lib/format";
import { statusHeadline } from "../../lib/shellStatus";
import { Diamond } from "./icons";
import { useShellData } from "./ShellData";
import { TONE_TEXT } from "./StatusIndicator";

/** The desktop status bar: overall state, document count, last backup and the running version (issue #27). */
export function StatusBar() {
  const { t, i18n } = useTranslation();
  const { status, catalog, version } = useShellData();
  return (
    <footer
      className="fixed inset-x-0 bottom-0 z-40 hidden h-7 items-center gap-5 overflow-hidden border-t border-line bg-void px-4 font-mono text-[11px] tracking-[0.14em] whitespace-nowrap text-text-4 uppercase lg:flex"
      data-testid="status-bar"
    >
      <span className={`flex items-center gap-1.5 ${TONE_TEXT[status.tone]}`}>
        <Diamond className="h-1.5 w-1.5" />
        {statusHeadline(t, status)}
      </span>
      {catalog && <span>{t("shell.statusBar.documents", { count: catalog.totalDocuments })}</span>}
      {status.lastBackupAt && (
        <span className={status.problemAreas.includes("backup") ? "text-danger" : undefined}>
          {t("shell.statusBar.lastBackup", { date: formatDateTime(status.lastBackupAt, i18n.language) })}
        </span>
      )}
      {version && <span className="ml-auto">{t("app.version", { commit: version.substring(0, 12) })}</span>}
    </footer>
  );
}
