import { useTranslation } from "react-i18next";
import type { SystemStatus } from "../api/types";
import { formatBytes, formatDateTime } from "../lib/format";
import { backupHintText, backupReasonText, diskName, diskUsageText, usedPercent } from "../lib/storage";
import type { BackupHealth, DiskSpace } from "../lib/storage";
import { Badge } from "./common";

/** The external backup and the free space of the disks on the status page (issue #100, ADR-0015). */
export function StorageSection({ storage }: { storage: SystemStatus["storage"] }) {
  const { t } = useTranslation();
  return (
    <section aria-labelledby="storage" data-testid="storage-section">
      <h3 id="storage" className="mb-1 font-semibold">
        {t("status.storageTitle")}
      </h3>
      <p className="mb-2 text-sm text-stone-600 dark:text-stone-400">{t("status.storageIntro")}</p>
      <ul className="flex flex-col gap-2">
        <BackupCard backup={storage.backup} />
        {storage.disks.map((disk) => (
          <DiskCard key={disk.name} disk={disk} />
        ))}
      </ul>
    </section>
  );
}

function BackupCard({ backup }: { backup: BackupHealth }) {
  const { t, i18n } = useTranslation();
  const failed = backup.state === "FAILED" || backup.state === "UNKNOWN";
  const stateHint = backup.state === "STALE" || backup.state === "INTERRUPTED";
  return (
    <li className="card flex flex-wrap items-start justify-between gap-2" data-testid="backup-card">
      <div className="min-w-0 flex-1">
        <p className="font-medium">{t("status.backup.title")}</p>
        <p className="text-sm text-stone-600 dark:text-stone-400">
          {t(`status.backup.summary.${backup.state}`, { reason: backupReasonText(t, backup.failureCode) })}
        </p>
        {failed && (
          <p className="text-sm text-red-700 dark:text-red-300" data-testid="backup-hint">
            {backupHintText(t, backup.failureCode)}
          </p>
        )}
        {stateHint && (
          <p className="text-sm text-red-700 dark:text-red-300" data-testid="backup-hint">
            {t(`status.backup.stateHint.${backup.state}`)}
          </p>
        )}
        <p className="text-xs text-stone-500">
          {t("status.lastSuccess", { date: backup.lastSuccessAt ? formatDateTime(backup.lastSuccessAt, i18n.language) : t("status.never") })}
          {backup.lastAttemptAt && <> · {t("status.backup.lastAttempt", { date: formatDateTime(backup.lastAttemptAt, i18n.language) })}</>}
          {backup.state === "RUNNING" && backup.runningSince && (
            <> · {t("status.backup.runningSince", { date: formatDateTime(backup.runningSince, i18n.language) })}</>
          )}
        </p>
      </div>
      <Badge state={`BACKUP_${backup.state}`} label={t(`status.backup.state.${backup.state}`)} />
    </li>
  );
}

function DiskCard({ disk }: { disk: DiskSpace }) {
  const { t, i18n } = useTranslation();
  const used = usedPercent(disk);
  const low = disk.state === "LOW";
  return (
    <li className="card flex flex-wrap items-start justify-between gap-2" data-testid={`disk-${disk.name}`}>
      <div className="min-w-0 flex-1">
        <p className="font-medium">{diskName(t, disk.name)}</p>
        <p className="text-sm text-stone-600 dark:text-stone-400">{diskUsageText(t, disk, i18n.language)}</p>
        {used !== null && (
          <div
            className="mt-1 h-2 w-full max-w-xs overflow-hidden rounded bg-stone-200 dark:bg-stone-700"
            role="meter"
            aria-label={diskName(t, disk.name)}
            aria-valuemin={0}
            aria-valuemax={100}
            aria-valuenow={used}
          >
            <div className={`h-full ${low ? "bg-red-600" : "bg-emerald-600"}`} style={{ width: `${used}%` }} />
          </div>
        )}
        {low && disk.lowBelowBytes != null && (
          <p className="text-sm text-red-700 dark:text-red-300">
            {t("status.disk.low", { limit: formatBytes(disk.lowBelowBytes, t, i18n.language) })}
          </p>
        )}
        <p className="text-xs text-stone-500">{t("status.disk.measuredAt", { date: formatDateTime(disk.measuredAt, i18n.language) })}</p>
      </div>
      <Badge state={`DISK_${disk.state}`} label={t(`status.disk.state.${disk.state}`)} />
    </li>
  );
}
