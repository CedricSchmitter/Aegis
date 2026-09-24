package dev.aegis.operations.application;

import dev.aegis.identity.application.api.MemberDirectory;
import dev.aegis.identity.application.api.MemberSummary;
import dev.aegis.notifications.application.api.NoticeDraft;
import dev.aegis.notifications.application.api.PublishNotice;
import dev.aegis.operations.application.api.BackupHealth;
import dev.aegis.operations.application.api.DiskSpace;
import dev.aegis.operations.application.api.JobHealthWatch;
import dev.aegis.operations.application.api.JobHealthWatch.Report;
import dev.aegis.operations.application.api.StorageHealth;
import dev.aegis.operations.application.api.StorageHealthWatch;
import dev.aegis.operations.application.ports.StorageIncidentStore;
import dev.aegis.operations.application.ports.StorageIncidentStore.StorageIncident;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Turns the backup and disk state of the status page into notices (ADR-0015), following the incident pattern of
 * ADR-0014: a failed, stale, interrupted or unreadable backup and a low disk each open one incident per subject, and
 * every active member receives one notice for it, deduplicated by the incident id. The backup incident closes when
 * the backup is OK again; a disk incident closes when the disk has clearly recovered
 * ({@link StorageHealthPolicy#recovered}). A running or not configured backup and an unmeasurable disk neither open
 * nor close an incident.
 *
 * <p>Each step is idempotent: while an incident is open, every check publishes its notices again and the notice
 * deduplication keeps one per member. Unlike the job watch there is no start grace: the backup runs on the host and
 * disk space does not depend on the application having started.
 */
public class StorageHealthWatchService implements StorageHealthWatch {
    static final String BACKUP_KIND = "BACKUP_FAILED";
    static final String DISK_KIND = "DISK_SPACE_LOW";
    static final String BACKUP_SUBJECT = "backup";
    private static final Set<String> BACKUP_ALERTING = Set.of(BackupHealth.FAILED, BackupHealth.STALE,
            BackupHealth.INTERRUPTED, BackupHealth.UNKNOWN);

    private final StorageHealthService storage;
    private final StorageIncidentStore incidents;
    private final MemberDirectory members;
    private final PublishNotice notices;
    private final Clock clock;

    public StorageHealthWatchService(StorageHealthService storage, StorageIncidentStore incidents,
            MemberDirectory members, PublishNotice notices, Clock clock) {
        this.storage = storage;
        this.incidents = incidents;
        this.members = members;
        this.notices = notices;
        this.clock = clock;
    }

    @Override
    public Report check() {
        StorageHealth health = storage.current();
        Instant now = clock.instant();
        Tally tally = new Tally();
        try {
            checkBackup(health.backup(), now, tally);
        } catch (RuntimeException failure) {
            tally.failed.add(BACKUP_SUBJECT);
        }
        for (DiskSpace disk : health.disks()) {
            try {
                checkDisk(disk, now, tally);
            } catch (RuntimeException failure) {
                tally.failed.add(diskSubject(disk.name()));
            }
        }
        return new Report(tally.opened, tally.resolved, tally.failed);
    }

    private void checkBackup(BackupHealth backup, Instant now, Tally tally) {
        Optional<StorageIncident> open = incidents.findOpen(BACKUP_SUBJECT);
        if (BackupHealth.OK.equals(backup.state())) {
            open.ifPresent(incident -> tally.resolve(incident, now));
            return;
        }
        if (open.isEmpty() && !BACKUP_ALERTING.contains(backup.state())) {
            return;
        }
        StorageIncident incident = open.orElseGet(() -> tally.open(new StorageIncident(UUID.randomUUID(),
                BACKUP_SUBJECT, backup.state(), backup.failureCode(), backup.lastSuccessAt(), null, null, now)));
        Map<String, String> params = new LinkedHashMap<>();
        params.put("state", incident.condition());
        if (incident.failureCode() != null) {
            params.put("reason", incident.failureCode());
        }
        if (incident.lastSuccessAt() != null) {
            params.put("lastSuccessAt", incident.lastSuccessAt().toString());
        }
        publish(BACKUP_KIND, incident, params, tally);
    }

    private void checkDisk(DiskSpace disk, Instant now, Tally tally) {
        if (DiskSpace.UNKNOWN.equals(disk.state())) {
            return;
        }
        String subject = diskSubject(disk.name());
        Optional<StorageIncident> open = incidents.findOpen(subject);
        if (open.isPresent() && StorageHealthPolicy.recovered(disk)) {
            tally.resolve(open.get(), now);
            return;
        }
        if (open.isEmpty() && !DiskSpace.LOW.equals(disk.state())) {
            return;
        }
        StorageIncident incident = open.orElseGet(() -> tally.open(new StorageIncident(UUID.randomUUID(), subject,
                DiskSpace.LOW, null, null, disk.freeBytes(), disk.totalBytes(), now)));
        Map<String, String> params = new LinkedHashMap<>();
        params.put("disk", disk.name());
        params.put("freeBytes", String.valueOf(incident.freeBytes()));
        params.put("totalBytes", String.valueOf(incident.totalBytes()));
        publish(DISK_KIND, incident, params, tally);
    }

    private void publish(String kind, StorageIncident incident, Map<String, String> params, Tally tally) {
        for (MemberSummary member : tally.recipients()) {
            notices.publish(new NoticeDraft(member.id(), kind, "storage-health:" + incident.id(), params, "STATUS",
                    incident.subject()));
        }
    }

    private static String diskSubject(String disk) {
        return "disk:" + disk;
    }

    /** Counts of one check and the active members, read once per check and only when a notice is due. */
    private final class Tally {
        private int opened;
        private int resolved;
        private final List<String> failed = new ArrayList<>();
        private List<MemberSummary> active;

        StorageIncident open(StorageIncident proposed) {
            opened++;
            return incidents.openIfAbsent(proposed);
        }

        void resolve(StorageIncident incident, Instant now) {
            incidents.resolve(incident.id(), now);
            resolved++;
        }

        List<MemberSummary> recipients() {
            if (active == null) {
                active = members.listMembers(JobHealthWatch.WATCH).stream().filter(MemberSummary::active).toList();
            }
            return active;
        }
    }
}
