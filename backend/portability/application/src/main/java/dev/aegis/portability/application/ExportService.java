package dev.aegis.portability.application;

import dev.aegis.contracts.application.api.ContractExportQuery;
import dev.aegis.contracts.application.api.ContractExportRecord;
import dev.aegis.documents.application.api.CatalogExport;
import dev.aegis.documents.application.api.DerivationPortability;
import dev.aegis.documents.application.api.DerivationPortability.DerivationRecord;
import dev.aegis.documents.application.api.DocumentExportQuery;
import dev.aegis.documents.application.api.DocumentExportRecord;
import dev.aegis.documents.application.api.OriginalContent;
import dev.aegis.documents.application.api.OriginalUnavailableException;
import dev.aegis.identity.application.api.MemberDirectory;
import dev.aegis.identity.application.api.MemberSummary;
import dev.aegis.planning.application.api.TaskExportQuery;
import dev.aegis.planning.application.api.TaskExportRecord;
import dev.aegis.portability.application.ExportFormat.AssignmentEntry;
import dev.aegis.portability.application.ExportFormat.CatalogFile;
import dev.aegis.portability.application.ExportFormat.ClassificationEntry;
import dev.aegis.portability.application.ExportFormat.ContractEntry;
import dev.aegis.portability.application.ExportFormat.ContractFactEntry;
import dev.aegis.portability.application.ExportFormat.ContractsFile;
import dev.aegis.portability.application.ExportFormat.DerivationEntry;
import dev.aegis.portability.application.ExportFormat.DerivationRangeEntry;
import dev.aegis.portability.application.ExportFormat.DocumentEntry;
import dev.aegis.portability.application.ExportFormat.DocumentsFile;
import dev.aegis.portability.application.ExportFormat.FolderEntry;
import dev.aegis.portability.application.ExportFormat.LabelEntry;
import dev.aegis.portability.application.ExportFormat.Manifest;
import dev.aegis.portability.application.ExportFormat.MemberEntry;
import dev.aegis.portability.application.ExportFormat.MembersFile;
import dev.aegis.portability.application.ExportFormat.SubjectEntry;
import dev.aegis.portability.application.ExportFormat.TaskEntry;
import dev.aegis.portability.application.ExportFormat.TasksFile;
import dev.aegis.portability.application.ExportFormat.ReviewEntry;
import dev.aegis.portability.application.ExportFormat.ReviewsFile;
import dev.aegis.review.application.api.ReviewPortability;
import dev.aegis.portability.application.api.CreateExport;
import dev.aegis.portability.application.api.ExportQueries;
import dev.aegis.portability.application.api.ExportSummary;
import dev.aegis.portability.application.ports.ExportSerializer;
import dev.aegis.portability.application.ports.ExportStore;
import dev.aegis.portability.application.ports.ExportStore.ExportStaging;
import dev.aegis.shared.Actor;
import dev.aegis.shared.HouseholdAccess;
import dev.aegis.shared.Trash;
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
 * Builds a complete export generation: originals, versioned JSON (documents with the page ranges they were created
 * from, tasks, contracts, members and the shared catalog), readable CSV/Markdown and a checksum manifest with relative links. Published atomically
 * through the store.
 */
public class ExportService implements CreateExport, ExportQueries {
    private static final DateTimeFormatter GENERATION_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'");
    private static final String UNSORTED = "(unsorted)";

    private static final DerivationPortability NO_DERIVATIONS = new DerivationPortability() {
        @Override
        public List<DerivationRecord> exportAll(Actor actor) {
            return List.of();
        }

        @Override
        public ImportResult importDerivation(Actor actor, DerivationRecord record) {
            throw new UnsupportedOperationException();
        }
    };

    private final DocumentExportQuery documents;
    private final DerivationPortability derivations;
    private final TaskExportQuery tasks;
    private final ContractExportQuery contracts;
    private final ReviewPortability reviews;
    private final MemberDirectory members;
    private final ExportStore store;
    private final ExportSerializer serializer;
    private final HouseholdAccess access;
    private final Clock clock;
    private final String producerVersion;

    public ExportService(DocumentExportQuery documents, TaskExportQuery tasks, ContractExportQuery contracts,
            MemberDirectory members, ExportStore store, ExportSerializer serializer, HouseholdAccess access,
            Clock clock, String producerVersion) {
        this(documents, tasks, contracts, new ReviewPortability() { public List<Record> exportAll(Actor actor) { return List.of(); } public void importAll(Actor actor,List<Record> records) {} }, members, store, serializer, access, clock, producerVersion);
    }

    public ExportService(DocumentExportQuery documents, TaskExportQuery tasks, ContractExportQuery contracts,
            ReviewPortability reviews, MemberDirectory members, ExportStore store, ExportSerializer serializer,
            HouseholdAccess access, Clock clock, String producerVersion) {
        this(documents, NO_DERIVATIONS, tasks, contracts, reviews, members, store, serializer, access, clock,
                producerVersion);
    }

    public ExportService(DocumentExportQuery documents, DerivationPortability derivations, TaskExportQuery tasks,
            ContractExportQuery contracts, ReviewPortability reviews, MemberDirectory members, ExportStore store,
            ExportSerializer serializer, HouseholdAccess access, Clock clock, String producerVersion) {
        this.documents = documents;
        this.derivations = derivations;
        this.tasks = tasks;
        this.contracts = contracts;
        this.reviews = reviews;
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
                        summary.taskCount(), summary.memberCount(), summary.contractCount(),
                        summary.missingOriginals(), location);
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
        CatalogExport catalog = documents.exportCatalog(actor);

        List<DocumentEntry> documentEntries = new ArrayList<>();
        List<DocumentExportRecord> records = documents.exportAll(actor);
        Map<String, String> filenames = exportFilenames(records);
        Map<String, DerivationEntry> derivedFrom = new LinkedHashMap<>();
        derivations.exportAll(actor).forEach(d -> derivedFrom.put(d.document().toString(), derivationEntry(d)));
        for (DocumentExportRecord record : records) {
            String originalPath = null;
            try {
                OriginalContent original = documents.originalForExport(actor, record.id());
                if (!record.checksumSha256().equals(sha256(original.bytes()))) {
                    throw new OriginalUnavailableException("original checksum mismatch");
                }
                originalPath = ExportFormat.ORIGINALS_DIR + filenames.get(record.id().toString());
                put(staging, checksums, originalPath, original.bytes());
            } catch (OriginalUnavailableException unavailable) {
                missingOriginals.add(record.id().toString());
            }
            documentEntries.add(new DocumentEntry(record.id().toString(), record.title(), record.titleOrigin(),
                    record.originalFilename(), filenames.get(record.id().toString()), record.contentType(),
                    record.sizeBytes(), record.checksumSha256(), record.uploadedBy().toString(),
                    record.uploadedAt(), record.subjects().stream()
                            .map(s -> new SubjectEntry(s.kind(), s.memberId() == null ? null : s.memberId().toString()))
                            .toList(), record.processingState(), record.paperlessDocumentId(), record.archivedAt(),
                    originalPath, classification(record), ExportAnalysis.toEntry(record.analysis()),
                    trashEntry(record.trash()), derivedFrom.get(record.id().toString())));
        }

        List<TaskEntry> taskEntries = new ArrayList<>();
        for (TaskExportRecord record : tasks.exportAll(actor)) {
            taskEntries.add(new TaskEntry(record.id().toString(), record.title(), record.note(),
                    text(record.ownerId()), record.status(), text(record.dueDate()), text(record.plannedDate()),
                    text(record.sourceDocumentId()), record.createdBy().toString(), record.createdAt(),
                    record.updatedAt(), record.completedAt(), text(record.completedBy()), text(record.contractId()),
                    record.contractName(), text(record.contractEnd()), text(record.cancellationDeadline()),
                    text(record.reminderDate()), record.contractVersion(), trashEntry(record.trash())));
        }

        List<ContractEntry> contractEntries = new ArrayList<>();
        for (ContractExportRecord record : contracts.exportAll(actor)) {
            contractEntries.add(contractEntry(record));
        }
        List<ReviewEntry> reviewEntries = reviews.exportAll(actor).stream().map(r -> new ReviewEntry(r.id().toString(),
                r.fingerprint(), r.producer(), r.sourceKind(), r.sourceId(), r.sourceVersion(), r.category(),
                r.reason(), r.priority(), r.titleKey(), r.params(), r.linkType(), r.linkId(), r.actions(), r.status(),
                r.createdAt(), r.updatedAt(), r.version(), r.decidedBy(), r.decidedAt())).toList();

        List<MemberEntry> memberEntries = memberList.stream().map(m -> new MemberEntry(m.id().toString(),
                m.username(), m.displayName(), m.locale().name(), m.active(), m.kind().name(),
                m.appearance().palette().name(), m.appearance().effects())).toList();
        List<LabelEntry> labelEntries = catalog.labels().stream().map(label -> new LabelEntry(
                label.id().toString(), label.facet(), label.builtinKey(), label.name(), label.displayName(),
                label.aliases(), label.status(),
                label.mergedInto() == null ? null : label.mergedInto().toString())).toList();
        List<FolderEntry> folderEntries = catalog.folders().stream().map(folder -> new FolderEntry(
                folder.id().toString(), folder.parentId() == null ? null : folder.parentId().toString(),
                folder.name(), folder.path(), folder.impliedLabelIds().stream().map(id -> id.toString()).toList(),
                folder.impliedSubjects().stream().map(s -> new SubjectEntry(s.kind(),
                        s.memberId() == null ? null : s.memberId().toString())).toList())).toList();

        put(staging, checksums, ExportFormat.DOCUMENTS_JSON,
                serializer.toJson(new DocumentsFile(ExportFormat.SCHEMA_VERSION, now, documentEntries)));
        put(staging, checksums, ExportFormat.TASKS_JSON,
                serializer.toJson(new TasksFile(ExportFormat.SCHEMA_VERSION, now, taskEntries)));
        put(staging, checksums, ExportFormat.MEMBERS_JSON,
                serializer.toJson(new MembersFile(ExportFormat.SCHEMA_VERSION, now, memberEntries)));
        put(staging, checksums, ExportFormat.CATALOG_JSON, serializer.toJson(
                new CatalogFile(ExportFormat.SCHEMA_VERSION, now, labelEntries, folderEntries)));
        put(staging, checksums, ExportFormat.CONTRACTS_JSON,
                serializer.toJson(new ContractsFile(ExportFormat.SCHEMA_VERSION, now, contractEntries)));
        put(staging, checksums, ExportFormat.REVIEWS_JSON,
                serializer.toJson(new ReviewsFile(ExportFormat.SCHEMA_VERSION, now, reviewEntries)));
        put(staging, checksums, ExportFormat.DOCUMENTS_CSV, documentsCsv(documentEntries, memberNames));
        put(staging, checksums, ExportFormat.TASKS_CSV, tasksCsv(taskEntries, memberNames, documentEntries));
        put(staging, checksums, ExportFormat.LABELS_CSV, labelsCsv(labelEntries, folderEntries));
        put(staging, checksums, ExportFormat.CONTRACTS_CSV,
                contractsCsv(contractEntries, documentEntries, memberNames));
        put(staging, checksums, ExportFormat.INDEX_MD, index(generation, now, documentEntries, taskEntries,
                contractEntries, memberNames, missingOriginals));

        Manifest manifest = new Manifest(ExportFormat.SCHEMA_VERSION, generation, now, producerVersion,
                documentEntries.size(), taskEntries.size(), memberEntries.size(), contractEntries.size(),
                missingOriginals, checksums);
        staging.write(ExportFormat.MANIFEST, serializer.toJson(manifest));
        return new ExportSummary(generation, now, documentEntries.size(), taskEntries.size(), memberEntries.size(),
                contractEntries.size(), List.copyOf(missingOriginals), null);
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

    /**
     * The friendly filename of every document. Names are unique by their short id; should two names still collide
     * (case-insensitively, as on Windows), every colliding name uses the full document id instead.
     */
    static Map<String, String> exportFilenames(List<DocumentExportRecord> records) {
        Map<String, List<DocumentExportRecord>> byName = new LinkedHashMap<>();
        for (DocumentExportRecord record : records) {
            byName.computeIfAbsent(record.filename().toLowerCase(java.util.Locale.ROOT), key -> new ArrayList<>())
                    .add(record);
        }
        Map<String, String> names = new LinkedHashMap<>();
        for (List<DocumentExportRecord> group : byName.values()) {
            for (DocumentExportRecord record : group) {
                names.put(record.id().toString(), group.size() == 1 ? record.filename() : withFullId(record));
            }
        }
        return names;
    }

    private static String withFullId(DocumentExportRecord record) {
        String full = record.id().toString().replace("-", "");
        String shortSuffix = "_" + full.substring(0, 8) + ".";
        int at = record.filename().lastIndexOf(shortSuffix);
        return at < 0 ? full + "_" + record.filename()
                : record.filename().substring(0, at) + "_" + full + "." + record.filename().substring(
                        at + shortSuffix.length());
    }

    private static ClassificationEntry classification(DocumentExportRecord record) {
        DocumentExportRecord.Classification source = record.classification();
        if (source == null) {
            return new ClassificationEntry(null, List.of(), List.of(), null, null);
        }
        List<AssignmentEntry> assignments = source.assignments().stream()
                .map(a -> new AssignmentEntry(a.labelId().toString(), a.facet(), a.displayName(), a.origin(),
                        a.reviewState(), a.confidence(), a.sourceRef(),
                        a.decidedBy() == null ? null : a.decidedBy().toString(), a.decidedAt()))
                .toList();
        DocumentExportRecord.Placement placement = source.placement();
        DocumentExportRecord.PlacementSuggestion suggestion = source.suggestion();
        return new ClassificationEntry(source.categoryId() == null ? null : source.categoryId().toString(),
                source.categoryPath(), assignments,
                placement == null ? null : new ExportFormat.PlacementEntry(placement.origin(),
                        placement.confidence(), placement.sourceRef(),
                        placement.by() == null ? null : placement.by().toString(), placement.at(),
                        placement.byMember()),
                suggestion == null ? null : new ExportFormat.PlacementSuggestionEntry(
                        suggestion.folderId() == null ? null : suggestion.folderId().toString(),
                        suggestion.anchorFolderId() == null ? null : suggestion.anchorFolderId().toString(),
                        suggestion.newPath(), suggestion.path(), suggestion.confidence(), suggestion.evidence(),
                        suggestion.runRef(), suggestion.model(), suggestion.generation(), suggestion.createdAt()));
    }

    private static DerivationEntry derivationEntry(DerivationRecord record) {
        return new DerivationEntry(record.pageEdit().toString(), record.createdBy().toString(), record.createdAt(),
                record.ranges().stream().map(range -> new DerivationRangeEntry(range.firstPage(), range.lastPage(),
                        range.sourceKind(), text(range.source()), range.firstSourcePage(), range.lastSourcePage(),
                        range.rotation())).toList());
    }

    /**
     * Where the pages of a document came from in words, for example {@code pages 1-2: pages 3-4 of Stromrechnung
     * (turned 90 degrees); page 3: scanned page 1}, so a reader without Aegis sees what was split or merged.
     */
    static String derivationSummary(DerivationEntry derivation, Map<String, String> titles) {
        if (derivation == null) {
            return "";
        }
        List<String> parts = new ArrayList<>();
        for (DerivationRangeEntry range : derivation.ranges()) {
            String source = "SCAN".equals(range.sourceKind()) ? "scanned "
                    + pages(range.firstSourcePage(), range.lastSourcePage())
                    : pages(range.firstSourcePage(), range.lastSourcePage()) + " of " + (range.sourceDocumentId()
                            == null ? "a deleted document"
                                    : titles.getOrDefault(range.sourceDocumentId(), range.sourceDocumentId()));
            parts.add(pages(range.firstPage(), range.lastPage()) + ": " + source
                    + (range.rotation() == 0 ? "" : " (turned " + range.rotation() + " degrees)"));
        }
        return String.join("; ", parts);
    }

    private static String pages(int first, int last) {
        return first == last ? "page " + first : "pages " + first + "-" + last;
    }

    private static ContractEntry contractEntry(ContractExportRecord record) {
        List<ContractFactEntry> facts = record.facts().stream().map(fact -> new ContractFactEntry(
                fact.id().toString(), fact.type(), fact.valueKind(), fact.value(), text(fact.validFrom()),
                text(fact.validUntil()), fact.reviewState(), fact.origin(), fact.confidence(),
                text(fact.sourceDocumentId()), fact.sourcePage(), fact.sourceQuote(), fact.runRef(),
                text(fact.observedBy()), fact.observedAt(), text(fact.decidedBy()), fact.decidedAt(),
                text(fact.supersededBy()), text(fact.corrects()), fact.note())).toList();
        return new ContractEntry(record.id().toString(), record.name(), record.status(), text(record.createdBy()),
                record.createdAt(), record.updatedAt(), facts, trashEntry(record.trash()));
    }

    private static ExportFormat.TrashEntry trashEntry(Trash trash) {
        return trash == null ? null : new ExportFormat.TrashEntry(trash.trashedBy().toString(), trash.trashedAt(),
                trash.restorableUntil());
    }

    private static String trashedAt(ExportFormat.TrashEntry trash) {
        return trash == null ? "" : trash.trashedAt().toString();
    }

    /** A readable marker for the index, so a reader without Aegis sees what the household had put aside. */
    private static String inTrash(ExportFormat.TrashEntry trash) {
        return trash == null ? "" : ", in the trash since " + trash.trashedAt();
    }

    /** One readable row per observation, so the fact history stays legible without Aegis. */
    private static String contractsCsv(List<ContractEntry> contracts, List<DocumentEntry> documents,
            Map<String, String> memberNames) {
        Map<String, String> titles = new LinkedHashMap<>();
        documents.forEach(document -> titles.put(document.id(), document.title()));
        List<List<String>> rows = new ArrayList<>();
        for (ContractEntry contract : contracts) {
            for (ContractFactEntry fact : contract.facts()) {
                rows.add(List.of(contract.id(), contract.name(), contract.status(), trashedAt(contract.trash()),
                        fact.id(), fact.type(),
                        fact.value(), nz(fact.validFrom()), nz(fact.validUntil()), fact.reviewState(),
                        fact.origin(), fact.confidence() == null ? "" : fact.confidence().toString(),
                        nz(fact.sourceDocumentId()),
                        fact.sourceDocumentId() == null ? "" : titles.getOrDefault(fact.sourceDocumentId(), ""),
                        fact.sourcePage() == null ? "" : fact.sourcePage().toString(), nz(fact.sourceQuote()),
                        fact.decidedBy() == null ? "" : memberNames.getOrDefault(fact.decidedBy(),
                                fact.decidedBy()),
                        fact.decidedAt() == null ? "" : fact.decidedAt().toString(), nz(fact.supersededBy()),
                        nz(fact.corrects()), nz(fact.note())));
            }
        }
        return Csv.render(List.of("contractId", "contract", "contractStatus", "contractTrashedAt", "factId",
                "factType", "value",
                "validFrom", "validUntil", "reviewState", "origin", "confidence", "sourceDocumentId",
                "sourceDocumentTitle", "sourcePage", "sourceQuote", "decidedBy", "decidedAt", "supersededBy",
                "corrects", "note"), rows);
    }

    private static String documentsCsv(List<DocumentEntry> entries, Map<String, String> memberNames) {
        Map<String, String> titles = new LinkedHashMap<>();
        entries.forEach(document -> titles.put(document.id(), document.title()));
        List<List<String>> rows = new ArrayList<>();
        for (DocumentEntry d : entries) {
            rows.add(List.of(d.id(), d.title(), d.originalFilename(), nz(d.filename()), String.valueOf(d.sizeBytes()),
                    d.checksumSha256(), memberNames.getOrDefault(d.uploadedBy(), d.uploadedBy()),
                    d.uploadedAt().toString(), d.subjects().stream().map(s -> s.memberId() == null ? s.kind()
                            : s.kind() + ":" + memberNames.getOrDefault(s.memberId(), s.memberId()))
                            .reduce((a, b) -> a + "; " + b).orElse(""),
                    d.processingState(), d.paperlessDocumentId() == null ? "" : d.paperlessDocumentId().toString(),
                    d.originalPath() == null ? "" : d.originalPath(), folder(d),
                    labels(d, "DOCUMENT_TYPE"), labels(d, "ORGANIZATION"), labels(d, "TOPIC"), labels(d, "TAG"),
                    d.analysis() == null || !"FOUND".equals(d.analysis().documentDateStatus()) ? ""
                            : nz(d.analysis().documentDate()),
                    d.analysis() == null ? "NOT_REQUESTED" : d.analysis().status(), trashedAt(d.trash()),
                    derivationSummary(d.derivedFrom(), titles)));
        }
        return Csv.render(List.of("id", "title", "originalFilename", "filename", "sizeBytes", "checksumSha256",
                "uploadedBy", "uploadedAt", "subjects", "processingState", "paperlessDocumentId", "originalPath",
                "folder", "documentType", "organizations", "topics", "tags", "documentDate", "analysisStatus",
                "trashedAt", "derivedFrom"), rows);
    }

    private static String labelsCsv(List<LabelEntry> labels, List<FolderEntry> folders) {
        Map<String, Long> folderCounts = new LinkedHashMap<>();
        folders.forEach(folder -> folder.impliedLabelIds()
                .forEach(label -> folderCounts.merge(label, 1L, Long::sum)));
        List<List<String>> rows = new ArrayList<>();
        for (LabelEntry label : labels) {
            rows.add(List.of(label.id(), label.facet(), label.label(),
                    label.builtinKey() == null ? "" : label.builtinKey(),
                    String.join("; ", label.aliases()), label.status(),
                    label.mergedInto() == null ? "" : label.mergedInto(),
                    String.valueOf(folderCounts.getOrDefault(label.id(), 0L))));
        }
        return Csv.render(List.of("id", "facet", "name", "builtinKey", "aliases", "status", "mergedInto",
                "folderMappings"), rows);
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
                    t.createdAt().toString(), t.completedAt() == null ? "" : t.completedAt().toString(),
                    nz(t.contractId()), nz(t.contractName()), nz(t.contractEnd()), nz(t.cancellationDeadline()),
                    nz(t.reminderDate()), trashedAt(t.trash())));
        }
        return Csv.render(List.of("id", "title", "note", "owner", "status", "dueDate", "plannedDate",
                "sourceDocumentId", "sourceDocumentTitle", "createdAt", "completedAt", "contractId",
                "contractName", "contractEnd", "cancellationDeadline", "reminderDate", "trashedAt"), rows);
    }

    private static String index(String generation, Instant now, List<DocumentEntry> documents,
            List<TaskEntry> tasks, List<ContractEntry> contracts, Map<String, String> memberNames,
            List<String> missingOriginals) {
        StringBuilder md = new StringBuilder();
        md.append("# Aegis export ").append(generation).append("\n\n");
        md.append("Exported at ").append(now).append(". Structured data: `data/*.json` (schema version ")
                .append(ExportFormat.SCHEMA_VERSION).append("), including the folder tree and label vocabulary in ")
                .append("`data/catalog.json`. Readable tables: `readable/*.csv`. Checksums: `manifest.json`.\n\n");
        md.append("## Documents by folder (").append(documents.size()).append(")\n\n");
        Map<String, List<DocumentEntry>> byFolder = new LinkedHashMap<>();
        documents.forEach(d -> byFolder.computeIfAbsent(folderOrUnsorted(d), key -> new ArrayList<>()).add(d));
        Map<String, String> titles = new LinkedHashMap<>();
        documents.forEach(d -> titles.put(d.id(), d.title()));
        byFolder.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(group -> {
            md.append("### ").append(group.getKey()).append("\n\n");
            for (DocumentEntry d : group.getValue()) {
                md.append("- ");
                if (d.originalPath() != null) {
                    md.append("[").append(d.title()).append("](").append(d.originalPath()).append(")");
                } else {
                    md.append(d.title()).append(" (original not included)");
                }
                md.append(" — uploaded ").append(d.uploadedAt()).append(" by ")
                        .append(memberNames.getOrDefault(d.uploadedBy(), d.uploadedBy())).append(", state ")
                        .append(d.processingState());
                md.append(inTrash(d.trash()));
                if (d.derivedFrom() != null) {
                    md.append(", created from ").append(derivationSummary(d.derivedFrom(), titles));
                }
                String chips = labelSummary(d);
                if (!chips.isEmpty()) {
                    md.append(", labels ").append(chips);
                }
                md.append(", id `").append(d.id()).append("`\n");
            }
            md.append("\n");
        });
        md.append("## Tasks (").append(tasks.size()).append(")\n\n");
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
            md.append(inTrash(t.trash())).append("\n");
        }
        md.append("\n## Contracts (").append(contracts.size()).append(")\n\n");
        for (ContractEntry contract : contracts) {
            long confirmed = contract.facts().stream().filter(fact -> "CONFIRMED".equals(fact.reviewState()))
                    .count();
            md.append("- ").append(contract.name()).append(" (").append(contract.status()).append("), ")
                    .append(confirmed).append(" confirmed of ").append(contract.facts().size())
                    .append(" observed facts").append(inTrash(contract.trash())).append(", id `")
                    .append(contract.id()).append("`\n");
            contract.facts().stream().filter(fact -> "CONFIRMED".equals(fact.reviewState()))
                    .forEach(fact -> md.append("  - ").append(fact.type()).append(": ").append(fact.value())
                            .append(fact.validFrom() == null ? "" : " (from " + fact.validFrom() + ")")
                            .append("\n"));
        }
        if (!missingOriginals.isEmpty()) {
            md.append("\n## Missing originals\n\n");
            missingOriginals.forEach(id -> md.append("- `").append(id).append("`\n"));
        }
        return md.toString();
    }

    private static String folder(DocumentEntry document) {
        return document.classification() == null ? "" : String.join(" / ", document.classification().folderPath());
    }

    private static String folderOrUnsorted(DocumentEntry document) {
        String path = folder(document);
        return path.isEmpty() ? UNSORTED : path;
    }

    private static String labels(DocumentEntry document, String facet) {
        if (document.classification() == null) {
            return "";
        }
        return document.classification().labels().stream()
                .filter(a -> facet.equals(a.facet()))
                .filter(a -> "CONFIRMED".equals(a.reviewState()) || "APPLIED".equals(a.reviewState()))
                .map(AssignmentEntry::label).reduce((a, b) -> a + "; " + b).orElse("");
    }

    private static String labelSummary(DocumentEntry document) {
        List<String> all = new ArrayList<>();
        for (String facet : List.of("DOCUMENT_TYPE", "ORGANIZATION", "TOPIC", "TAG")) {
            String value = labels(document, facet);
            if (!value.isEmpty()) {
                all.add(value);
            }
        }
        return String.join("; ", all);
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
