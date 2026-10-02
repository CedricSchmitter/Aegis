import type { TFunction } from "i18next";
import type { SystemStatus } from "../api/types";
import { formatBytes } from "./format";

export type BackupHealth = SystemStatus["storage"]["backup"];
export type DiskSpace = SystemStatus["storage"]["disks"][number];

/**
 * Localized reasons for the failure codes the backup script (ops/backup/backup.py) and the state reader write
 * (ADR-0015). Each reason has a short hint on what to check. The stable code stays in the API and the
 * notice parameters; only the presentation turns it into words.
 */
export const BACKUP_REASONS = [
  "notMounted",
  "markerMissing",
  "destinationChanged",
  "destinationUnreadable",
  "misconfigured",
  "repositoryMissing",
  "repositoryUnreadable",
  "captureFailed",
  "restartFailed",
  "checkFailed",
  "stateUnreadable",
  "unknown",
] as const;

export type BackupReason = (typeof BACKUP_REASONS)[number];

const CODES: Record<string, BackupReason> = {
  "destination.notMounted": "notMounted",
  "destination.markerMissing": "markerMissing",
  "destination.changed": "destinationChanged",
  "destination.unreadable": "destinationUnreadable",
  "repository.outsideMount": "misconfigured",
  "password.missing": "misconfigured",
  "repository.notInitialized": "repositoryMissing",
  "repository.unreadable": "repositoryUnreadable",
  "capture.failed": "captureFailed",
  "services.restartFailed": "restartFailed",
  "check.failed": "checkFailed",
  "state.unreadable": "stateUnreadable",
};

/** The reason for a backup failure code; unknown or missing codes get the generic reason, never the raw code. */
export function backupReason(code: string | null | undefined): BackupReason {
  return (code && CODES[code]) || "unknown";
}

export function backupReasonText(t: TFunction, code: string | null | undefined): string {
  return String(t(`status.backup.reason.${backupReason(code)}`));
}

export function backupHintText(t: TFunction, code: string | null | undefined): string {
  return String(t(`status.backup.hint.${backupReason(code)}`));
}

/** Share of the disk in use, 0–100, or null when the disk could not be measured. */
export function usedPercent(disk: DiskSpace): number | null {
  if (disk.totalBytes == null || disk.freeBytes == null || disk.totalBytes <= 0) return null;
  return Math.min(100, Math.max(0, Math.round(((disk.totalBytes - disk.freeBytes) / disk.totalBytes) * 100)));
}

export function diskName(t: TFunction, name: string): string {
  return String(t(`status.disk.name.${name}`, { defaultValue: name }));
}

/** "64 GB frei von 93 GB" for a measured disk. */
export function diskUsageText(t: TFunction, disk: DiskSpace, language: string): string {
  if (disk.totalBytes == null || disk.freeBytes == null) return String(t("status.disk.unmeasured"));
  return String(
    t("status.disk.free", {
      free: formatBytes(disk.freeBytes, t, language),
      total: formatBytes(disk.totalBytes, t, language),
    }),
  );
}
