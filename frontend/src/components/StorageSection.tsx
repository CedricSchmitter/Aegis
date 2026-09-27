import { useTranslation } from "react-i18next";
import type { SystemStatus } from "../api/types";
import { formatBytes, formatDateTime } from "../lib/format";
import { backupHintText, backupReasonText, diskName, diskUsageText, usedPercent } from "../lib/storage";
import type { BackupHealth, DiskSpace } from "../lib/storage";
import { StateWord } from "./common";

/** The external backup and the free space of the disks on the status page (issue #100, ADR-0015). */
export function StorageSection({ storage }: { storage: SystemStatus["storage"] }) {
  const { t } = useTranslation();
  return (
    <section aria-labelledby="storage" className="flex flex-col gap-2" data-testid="storage-section">
      <h3 id="storage" className="ui-sec">
        {t("status.storageTitle")}
      </h3>
      <p className="text-[13px] leading-relaxed text-text-3">{t("status.storageIntro")}</p>
      <ul className="border-t border-line">
        <BackupRow backup={storage.backup} />
        {storage.disks.map((disk) => (
          <DiskRow key={disk.name} disk={disk} />
        ))}
      </ul>
    </section>
  );
}

function BackupRow({ backup }: { backup: BackupHealth }) {
  const { t, i18n } = useTranslation();
  const failed = backup.state === "FAILED" || backup.state === "UNKNOWN";
  const stateHint = backup.state === "STALE" || backup.state === "INTERRUPTED";
  const broken = backup.state === "FAILED" || backup.state === "STALE";
  return (
    <li className="ui-row items-start" data-tone={broken ? "danger" : undefined} data-testid="backup-card">
      <span className="ui-row-text">
        <b>{t("status.backup.title")}</b>
        <span>{t(`status.backup.summary.${backup.state}`, { reason: backupReasonText(t, backup.failureCode) })}</span>
        {failed && (
          <small className="text-[13px] leading-snug text-text-2" data-testid="backup-hint">
            {backupHintText(t, backup.failureCode)}
          </small>
        )}
        {stateHint && (
          <small className="text-[13px] leading-snug text-text-2" data-testid="backup-hint">
            {t(`status.backup.stateHint.${backup.state}`)}
          </small>
        )}
        <small className="font-mono text-xs text-text-4">
          {t("status.lastSuccess", { date: backup.lastSuccessAt ? formatDateTime(backup.lastSuccessAt, i18n.language) : t("status.never") })}
          {backup.lastAttemptAt && <> · {t("status.backup.lastAttempt", { date: formatDateTime(backup.lastAttemptAt, i18n.language) })}</>}
          {backup.state === "RUNNING" && backup.runningSince && (
            <> · {t("status.backup.runningSince", { date: formatDateTime(backup.runningSince, i18n.language) })}</>
          )}
        </small>
      </span>
      <StateWord state={`BACKUP_${backup.state}`} label={t(`status.backup.state.${backup.state}`)} />
    </li>
  );
}

function DiskRow({ disk }: { disk: DiskSpace }) {
  const { t, i18n } = useTranslation();
  const used = usedPercent(disk);
  const low = disk.state === "LOW";
  return (
    <li className="ui-row items-start" data-tone={low ? "danger" : undefined} data-testid={`disk-${disk.name}`}>
      <span className="ui-row-text">
        <b>{diskName(t, disk.name)}</b>
        <span>{diskUsageText(t, disk, i18n.language)}</span>
        {used !== null && (
          <span
            className="ui-meter"
            data-tone={low ? "danger" : undefined}
            role="meter"
            aria-label={diskName(t, disk.name)}
            aria-valuemin={0}
            aria-valuemax={100}
            aria-valuenow={used}
          >
            <span style={{ width: `${used}%` }} />
          </span>
        )}
        {low && disk.lowBelowBytes != null && (
          <small className="text-[13px] leading-snug text-text-2">
            {t("status.disk.low", { limit: formatBytes(disk.lowBelowBytes, t, i18n.language) })}
          </small>
        )}
        <small className="font-mono text-xs text-text-4">{t("status.disk.measuredAt", { date: formatDateTime(disk.measuredAt, i18n.language) })}</small>
      </span>
      <StateWord state={`DISK_${disk.state}`} label={t(`status.disk.state.${disk.state}`)} />
    </li>
  );
}
