package dev.aegis.portability.application;

import dev.aegis.documents.application.api.DocumentExportQuery;
import dev.aegis.documents.application.api.DocumentExportRecord;
import dev.aegis.documents.application.api.OriginalContent;
import dev.aegis.documents.application.api.OriginalUnavailableException;
import dev.aegis.identity.application.api.MemberDirectory;
import dev.aegis.identity.application.api.MemberSummary;
import dev.aegis.planning.application.api.TaskExportQuery;
import dev.aegis.planning.application.api.TaskExportRecord;
import dev.aegis.portability.application.ExportFormat.DocumentEntry;
import dev.aegis.portability.application.ExportFormat.DocumentsFile;
import dev.aegis.portability.application.ExportFormat.Manifest;
import dev.aegis.portability.application.ExportFormat.MemberEntry;
import dev.aegis.portability.application.ExportFormat.MembersFile;
import dev.aegis.portability.application.ExportFormat.SubjectEntry;
import dev.aegis.portability.application.ExportFormat.TaskEntry;
import dev.aegis.portability.application.ExportFormat.TasksFile;
import dev.aegis.portability.application.api.CreateExport;
import dev.aegis.portability.application.api.ExportQueries;
import dev.aegis.portability.application.api.ExportSummary;
import dev.aegis.portability.application.ports.ExportSerializer;
import dev.aegis.portability.application.ports.ExportStore;
import dev.aegis.portability.application.ports.ExportStore.ExportStaging;
import dev.aegis.shared.Actor;
import dev.aegis.shared.HouseholdAccess;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Builds a complete export generation: originals, versioned JSON, readable CSV/Markdown and a checksum manifest
 * with relative links. Published atomically through the store.
 */
public class ExportService implements CreateExport, ExportQueries {
    private static final DateTimeFormatter GENERATION_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'");
    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ISO_LOCAL_DATE;

    private final DocumentExportQuery documents;
    private final TaskExportQuery tasks;
    private final MemberDirectory members;
    private final ExportStore store;
    private final ExportSerializer serializer;
    private final HouseholdAccess access;
    private final Clock clock;
    private final String producerVersion;

    public ExportService(DocumentExportQuery documents, TaskExportQuery tasks, MemberDirectory members,
            ExportStore store, ExportSerializer serializer, HouseholdAccess access, Clock clock,
            String producerVersion) {
        this.documents = documents;
        this.tasks = tasks;
        this.members = members;
        this.store = store;
        this.serializer = serializer;
        this.access = access;
        this.clock = clock;
        this.producerVersion = producerVersion;
    }

    @Override
    public ExportSummary export(Actor actor) {
        access.requireMember(actor);
        Instant now = clock.instant();
        String generation = GENERATION_FORMAT.format(now.atZone(ZoneId.of("UTC"))) + "-" + java.util.UUID.randomUUID();
        try {
            ExportStaging staging = store.beginGeneration(generation);
            try {
                ExportSummary summary = write(actor, staging, generation, now);
                String location = staging.publish();
                return new ExportSummary(summary.generation(), summary.createdAt(), summary.documentCount(),
                        summary.taskCount(), summary.memberCount(), summary.missingOriginals(), location);
            } catch (IOException | RuntimeException failure) {
                staging.discard();
                throw failure;
            }
        } catch (IOException e) {
            throw new UncheckedIOException("export generation " + generation + " failed", e);
        }
    }

    private ExportSummary write(Actor actor, ExportStaging staging, String generation, Instant now)
            throws IOException {
        Map<String, String> checksums = new LinkedHashMap<>();
        List<String> missingOriginals = new ArrayList<>();
        List<MemberSummary> memberList = members.listMembers(actor);
        Map<String, String> memberNames = new LinkedHashMap<>();
        memberList.forEach(m -> memberNames.put(m.id().toString(), m.displayName()));

        List<DocumentEntry> documentEntries = new ArrayList<>();
        for (DocumentExportRecord record : documents.exportAll(actor)) {
            String originalPath = null;
            try {
                OriginalContent original = documents.originalForExport(actor, record.id());
                originalPath = ExportFormat.ORIGINALS_DIR + originalFilename(record);
                put(staging, checksums, originalPath, original.bytes());
            } catch (OriginalUnavailableException unavailable) {
                missingOriginals.add(record.id().toString());
            }
            documentEntries.add(new DocumentEntry(record.id().toString(), record.title(), record.originalFilename(),
                    record.contentType(), record.sizeBytes(), record.checksumSha256(), record.uploadedBy().toString(),
                    record.uploadedAt(), record.subjects().stream()
                            .map(s -> new SubjectEntry(s.kind(), s.memberId() == null ? null : s.memberId().toString()))
                            .toList(), record.processingState(), record.paperlessDocumentId(), record.archivedAt(),
                    originalPath));
        }

        List<TaskEntry> taskEntries = new ArrayList<>();
        for (TaskExportRecord record : tasks.exportAll(actor)) {
            taskEntries.add(new TaskEntry(record.id().toString(), record.title(), record.note(),
                    text(record.ownerId()), record.status(), text(record.dueDate()), text(record.plannedDate()),
                    text(record.sourceDocumentId()), record.createdBy().toString(), record.createdAt(),
                    record.updatedAt(), record.completedAt(), text(record.completedBy())));
        }

        List<MemberEntry> memberEntries = memberList.stream().map(m -> new MemberEntry(m.id().toString(),
                m.username(), m.displayName(), m.locale().name(), m.active())).toList();

        put(staging, checksums, ExportFormat.DOCUMENTS_JSON,
                serializer.toJson(new DocumentsFile(ExportFormat.SCHEMA_VERSION, now, documentEntries)));
        put(staging, checksums, ExportFormat.TASKS_JSON,
                serializer.toJson(new TasksFile(ExportFormat.SCHEMA_VERSION, now, taskEntries)));
        put(staging, checksums, ExportFormat.MEMBERS_JSON,
                serializer.toJson(new MembersFile(ExportFormat.SCHEMA_VERSION, now, memberEntries)));
        put(staging, checksums, ExportFormat.DOCUMENTS_CSV, documentsCsv(documentEntries, memberNames));
        put(staging, checksums, ExportFormat.TASKS_CSV, tasksCsv(taskEntries, memberNames, documentEntries));
        put(staging, checksums, ExportFormat.INDEX_MD,
                index(generation, now, documentEntries, taskEntries, memberNames, missingOriginals));

        Manifest manifest = new Manifest(ExportFormat.SCHEMA_VERSION, generation, now, producerVersion,
                documentEntries.size(), taskEntries.size(), memberEntries.size(), missingOriginals, checksums);
        staging.write(ExportFormat.MANIFEST, serializer.toJson(manifest));
        return new ExportSummary(generation, now, documentEntries.size(), taskEntries.size(), memberEntries.size(),
                List.copyOf(missingOriginals), null);
    }

    @Override
    public List<ExportSummary> list(Actor actor) {
        access.requireMember(actor);
        try {
            return store.listGenerations();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public Optional<ExportSummary> latest(Actor actor) {
        access.requireMember(actor);
        try {
            return store.latest();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static String originalFilename(DocumentExportRecord record) {
        String date = DATE_FORMAT.format(record.uploadedAt().atZone(ZoneId.of("Europe/Berlin")).toLocalDate());
        String shortId = record.id().toString().substring(0, 8);
        return date + "_" + Slugs.slug(record.title(), 60) + "_" + shortId + ".pdf";
    }

    private static void put(ExportStaging staging, Map<String, String> checksums, String path, byte[] bytes)
            throws IOException {
        staging.write(path, bytes);
        checksums.put(path, sha256(bytes));
    }

    private static void put(ExportStaging staging, Map<String, String> checksums, String path, String text)
            throws IOException {
        put(staging, checksums, path, text.getBytes(StandardCharsets.UTF_8));
    }

    private static String documentsCsv(List<DocumentEntry> entries, Map<String, String> memberNames) {
        List<List<String>> rows = new ArrayList<>();
        for (DocumentEntry d : entries) {
            rows.add(List.of(d.id(), d.title(), d.originalFilename(), String.valueOf(d.sizeBytes()),
                    d.checksumSha256(), memberNames.getOrDefault(d.uploadedBy(), d.uploadedBy()),
                    d.uploadedAt().toString(), d.subjects().stream().map(s -> s.memberId() == null ? s.kind()
                            : s.kind() + ":" + memberNames.getOrDefault(s.memberId(), s.memberId()))
                            .reduce((a, b) -> a + "; " + b).orElse(""),
                    d.processingState(), d.paperlessDocumentId() == null ? "" : d.paperlessDocumentId().toString(),
                    d.originalPath() == null ? "" : d.originalPath()));
        }
        return Csv.render(List.of("id", "title", "originalFilename", "sizeBytes", "checksumSha256", "uploadedBy",
                "uploadedAt", "subjects", "processingState", "paperlessDocumentId", "originalPath"), rows);
    }

    private static String tasksCsv(List<TaskEntry> entries, Map<String, String> memberNames,
            List<DocumentEntry> documents) {
        Map<String, String> titles = new LinkedHashMap<>();
        documents.forEach(d -> titles.put(d.id(), d.title()));
        List<List<String>> rows = new ArrayList<>();
        for (TaskEntry t : entries) {
            rows.add(List.of(t.id(), t.title(), t.note(), t.ownerId() == null ? ""
                    : memberNames.getOrDefault(t.ownerId(), t.ownerId()), t.status(), nz(t.dueDate()),
                    nz(t.plannedDate()), nz(t.sourceDocumentId()),
                    t.sourceDocumentId() == null ? "" : titles.getOrDefault(t.sourceDocumentId(), ""),
                    t.createdAt().toString(), t.completedAt() == null ? "" : t.completedAt().toString()));
        }
        return Csv.render(List.of("id", "title", "note", "owner", "status", "dueDate", "plannedDate",
                "sourceDocumentId", "sourceDocumentTitle", "createdAt", "completedAt"), rows);
    }

    private static String index(String generation, Instant now, List<DocumentEntry> documents,
            List<TaskEntry> tasks, Map<String, String> memberNames, List<String> missingOriginals) {
        StringBuilder md = new StringBuilder();
        md.append("# Aegis export ").append(generation).append("\n\n");
        md.append("Exported at ").append(now).append(". Structured data: `data/*.json` (schema version ")
                .append(ExportFormat.SCHEMA_VERSION).append("). Readable tables: `readable/*.csv`. ")
                .append("Checksums: `manifest.json`.\n\n");
        md.append("## Documents (").append(documents.size()).append(")\n\n");
        for (DocumentEntry d : documents) {
            md.append("- ");
            if (d.originalPath() != null) {
                md.append("[").append(d.title()).append("](").append(d.originalPath()).append(")");
            } else {
                md.append(d.title()).append(" (original not included)");
            }
            md.append(" — uploaded ").append(d.uploadedAt()).append(" by ")
                    .append(memberNames.getOrDefault(d.uploadedBy(), d.uploadedBy())).append(", state ")
                    .append(d.processingState()).append(", id `").append(d.id()).append("`\n");
        }
        md.append("\n## Tasks (").append(tasks.size()).append(")\n\n");
        Map<String, DocumentEntry> byId = new LinkedHashMap<>();
        documents.forEach(d -> byId.put(d.id(), d));
        for (TaskEntry t : tasks) {
            md.append("- ").append(t.status().equals("DONE") ? "[x] " : "[ ] ").append(t.title());
            if (t.ownerId() != null) {
                md.append(" — ").append(memberNames.getOrDefault(t.ownerId(), t.ownerId()));
            }
            if (t.dueDate() != null) {
                md.append(", due ").append(t.dueDate());
            }
            if (t.plannedDate() != null) {
                md.append(", planned ").append(t.plannedDate());
            }
            DocumentEntry source = t.sourceDocumentId() == null ? null : byId.get(t.sourceDocumentId());
            if (source != null && source.originalPath() != null) {
                md.append(", source [").append(source.title()).append("](").append(source.originalPath()).append(")");
            } else if (t.sourceDocumentId() != null) {
                md.append(", source document `").append(t.sourceDocumentId()).append("`");
            }
            md.append("\n");
        }
        if (!missingOriginals.isEmpty()) {
            md.append("\n## Missing originals\n\n");
            missingOriginals.forEach(id -> md.append("- `").append(id).append("`\n"));
        }
        return md.toString();
    }

    private static String text(Object value) {
        return value == null ? null : value.toString();
    }

    private static String nz(String value) {
        return value == null ? "" : value;
    }

    static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
