package dev.aegis.portability.application;

import dev.aegis.contracts.application.api.ContractExportRecord;
import dev.aegis.contracts.application.api.ContractExportRecord.FactExportRecord;
import dev.aegis.contracts.application.api.ImportContract;
import dev.aegis.documents.application.api.DerivationPortability;
import dev.aegis.documents.application.api.DerivationPortability.DerivationRecord;
import dev.aegis.documents.application.api.DerivationPortability.RangeRecord;
import dev.aegis.documents.application.api.ImportCatalog;
import dev.aegis.documents.application.api.ImportCatalog.AssignmentImport;
import dev.aegis.documents.application.api.ImportCatalog.CatalogImportResult;
import dev.aegis.documents.application.api.ImportCatalog.ClassificationOutcome;
import dev.aegis.documents.application.api.ImportCatalog.FolderImport;
import dev.aegis.documents.application.api.ImportCatalog.LabelImport;
import dev.aegis.documents.application.api.ImportCatalog.PlacementImport;
import dev.aegis.documents.application.api.ImportCatalog.SuggestionImport;
import dev.aegis.documents.application.api.ImportDocument;
import dev.aegis.documents.application.api.ImportDocumentAnalysis;
import dev.aegis.documents.application.api.ImportDocument.ImportDocumentCommand;
import dev.aegis.documents.application.api.SubjectView;
import dev.aegis.identity.application.api.MemberAppearance;
import dev.aegis.identity.application.api.MemberDirectory;
import dev.aegis.identity.application.api.MemberKind;
import dev.aegis.identity.application.api.MemberSummary;
import dev.aegis.identity.application.api.ProvisionMembers;
import dev.aegis.identity.application.api.ProvisionMembers.ProvisionMemberCommand;
import dev.aegis.planning.application.api.ImportTask;
import dev.aegis.planning.application.api.TaskExportRecord;
import dev.aegis.portability.application.ExportFormat.CatalogFile;
import dev.aegis.portability.application.ExportFormat.ClassificationEntry;
import dev.aegis.portability.application.ExportFormat.ContractEntry;
import dev.aegis.portability.application.ExportFormat.ContractsFile;
import dev.aegis.portability.application.ExportFormat.DerivationEntry;
import dev.aegis.portability.application.ExportFormat.DocumentEntry;
import dev.aegis.portability.application.ExportFormat.DocumentsFile;
import dev.aegis.portability.application.ExportFormat.Manifest;
import dev.aegis.portability.application.ExportFormat.MemberEntry;
import dev.aegis.portability.application.ExportFormat.MembersFile;
import dev.aegis.portability.application.ExportFormat.TaskEntry;
import dev.aegis.portability.application.ExportFormat.TasksFile;
import dev.aegis.portability.application.ExportFormat.ReviewsFile;
import dev.aegis.review.application.api.ReviewPortability;
import dev.aegis.portability.application.api.RestoreExport;
import dev.aegis.portability.application.ports.ExportSerializer;
import dev.aegis.portability.application.ports.ExportSource;
import dev.aegis.portability.application.ports.ExportSourceOpener;
import dev.aegis.shared.Actor;
import dev.aegis.shared.ContractId;
import dev.aegis.shared.DocumentId;
import dev.aegis.shared.HouseholdAccess;
import dev.aegis.shared.InvalidInputException;
import dev.aegis.shared.MemberId;
import dev.aegis.shared.ProductLocale;
import dev.aegis.shared.TaskId;
import dev.aegis.shared.Trash;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Restore into a (fresh or existing) instance. Verifies manifest checksums, maps members by username, restores the
 * shared catalog, re-imports originals under their stable ids and restores tasks and contracts with their source
 * links. The subject "both" of an export before schema 9 becomes one subject per person of this household, the
 * same mapping the startup migration applies (ADR-0017). Once all documents are back, a document created by the
 * page editor gets its page ranges back; a range whose source is not stored here names a deleted source (ADR-0024).
 * An exported classification is applied only to a document that is still unclassified, an existing derivation or
 * contract is left untouched, so a repeated restore never overwrites newer local edits.
 */
public class RestoreService implements RestoreExport {
    private final ExportSourceOpener opener;
    private final ExportSerializer serializer;
    private final MemberDirectory members;
    private final ProvisionMembers provisioning;
    private final ImportDocument importDocument;
    private final ImportCatalog importCatalog;
    private final ImportDocumentAnalysis importAnalysis;
    private final ImportTask importTask;
    private final ImportContract importContract;
    private final ReviewPortability reviews;
    private final DerivationPortability derivations;
    private final HouseholdAccess access;
    private final SecureRandom random = new SecureRandom();

    public RestoreService(ExportSourceOpener opener, ExportSerializer serializer, MemberDirectory members,
            ProvisionMembers provisioning, ImportDocument importDocument, ImportCatalog importCatalog,
            ImportDocumentAnalysis importAnalysis, ImportTask importTask, ImportContract importContract,
            ReviewPortability reviews, DerivationPortability derivations, HouseholdAccess access) {
        this.opener = opener;
        this.serializer = serializer;
        this.members = members;
        this.provisioning = provisioning;
        this.importDocument = importDocument;
        this.importCatalog = importCatalog;
        this.importAnalysis = importAnalysis;
        this.importTask = importTask;
        this.importContract = importContract;
        this.reviews = reviews;
        this.derivations = derivations;
        this.access = access;
    }

    @Override
    public RestoreReport restore(Actor actor, String sourceLocation) {
        access.requireMember(actor);
        try {
            ExportSource source = opener.open(sourceLocation);
            Manifest manifest = read(source, ExportFormat.MANIFEST, Manifest.class);
            if (!ExportFormat.SUPPORTED_SCHEMA_VERSIONS.contains(manifest.schemaVersion())) {
                throw new InvalidInputException("export.schema.unsupported",
                        "Unsupported export schema version " + manifest.schemaVersion());
            }
            List<String> warnings = new ArrayList<>();
            verifyChecksums(source, manifest, warnings);

            Map<String, String> memberMapping = mapMembers(actor, read(source, ExportFormat.MEMBERS_JSON,
                    MembersFile.class), warnings);
            List<MemberId> persons = members.listMembers(actor).stream()
                    .filter(member -> member.active() && member.isPerson()).map(MemberSummary::id).toList();
            CatalogImportResult catalog = restoreCatalog(actor, source, persons, warnings);
            DocumentsFile documentsFile = read(source, ExportFormat.DOCUMENTS_JSON, DocumentsFile.class);
            int[] documentCounts = restoreDocuments(actor, source, documentsFile, memberMapping, persons, catalog,
                    warnings);
            int[] derivationCounts = restoreDerivations(actor, documentsFile, memberMapping, warnings);
            int[] taskCounts = restoreTasks(actor, read(source, ExportFormat.TASKS_JSON, TasksFile.class),
                    memberMapping);
            int[] contractCounts = restoreContracts(actor, source, memberMapping);
            restoreReviews(actor, source, memberMapping);
            return new RestoreReport(manifest.generation(), documentCounts[0], documentCounts[1], taskCounts[0],
                    taskCounts[1], catalog.labelsCreated(), catalog.foldersCreated(), documentCounts[2],
                    documentCounts[3], documentCounts[4], contractCounts[0], contractCounts[1], derivationCounts[0],
                    derivationCounts[1], memberMapping, warnings);
        } catch (IOException e) {
            throw new UncheckedIOException("restore from " + sourceLocation + " failed", e);
        }
    }

    private void restoreReviews(Actor actor, ExportSource source, Map<String,String> memberMapping) throws IOException {
        if (!source.exists(ExportFormat.REVIEWS_JSON)) return;
        var records = read(source, ExportFormat.REVIEWS_JSON, ReviewsFile.class).reviews().stream().map(r ->
                new ReviewPortability.Record(UUID.fromString(r.id()),r.fingerprint(),r.producer(),r.sourceKind(),
                        r.sourceId(),r.sourceVersion(),r.category(),r.reason(),r.priority(),r.titleKey(),r.params(),
                        r.linkType(),r.linkId(),r.actions(),r.status(),r.createdAt(),r.updatedAt(),r.version(),
                        r.decidedBy()==null?null:memberMapping.getOrDefault(r.decidedBy(),r.decidedBy()),r.decidedAt()))
                .toList();
        reviews.importAll(actor, records);
    }

    private void verifyChecksums(ExportSource source, Manifest manifest, List<String> warnings) throws IOException {
        for (Map.Entry<String, String> entry : manifest.fileChecksumsSha256().entrySet()) {
            if (!source.exists(entry.getKey())) {
                throw new InvalidInputException("export.file.missing", "Export file missing: " + entry.getKey());
            }
            String actual = ExportService.sha256(source.read(entry.getKey()));
            if (!actual.equals(entry.getValue())) {
                throw new InvalidInputException("export.checksum.mismatch",
                        "Checksum mismatch for " + entry.getKey());
            }
        }
        if (!manifest.missingOriginals().isEmpty()) {
            warnings.add("export lists " + manifest.missingOriginals().size() + " missing originals");
        }
    }

    /** Folder tree and vocabulary; absent in schema 1 exports, which restore without classifications. */
    private CatalogImportResult restoreCatalog(Actor actor, ExportSource source, List<MemberId> persons,
            List<String> warnings) throws IOException {
        if (!source.exists(ExportFormat.CATALOG_JSON)) {
            return new CatalogImportResult(Map.of(), Map.of(), 0, 0, List.of());
        }
        CatalogFile catalog = read(source, ExportFormat.CATALOG_JSON, CatalogFile.class);
        List<LabelImport> labels = catalog.labels().stream().map(label -> new LabelImport(
                UUID.fromString(label.id()), label.facet(), label.builtinKey(), label.name(), label.aliases(),
                label.status(), label.mergedInto() == null ? null : UUID.fromString(label.mergedInto()))).toList();
        List<FolderImport> folders = catalog.folders().stream().map(folder -> new FolderImport(
                UUID.fromString(folder.id()),
                folder.parentId() == null ? null : UUID.fromString(folder.parentId()), folder.name(),
                folder.impliedLabelIds().stream().map(UUID::fromString).toList(),
                LegacySubjects.expand(folder.impliedSubjects().stream().map(s -> new SubjectView(s.kind(),
                        s.memberId() == null ? null : MemberId.of(s.memberId()))).toList(), persons))).toList();
        CatalogImportResult result = importCatalog.importCatalog(actor, labels, folders);
        warnings.addAll(result.warnings());
        return result;
    }

    /**
     * The exported palette and effects of a member that the restore has to create. Files before schema 10 carry
     * neither and an unknown palette name is a look, not data, so both give the default instead of failing the
     * restore. An existing member keeps their own choice, like their locale.
     */
    static MemberAppearance appearance(MemberEntry entry) {
        MemberAppearance.Palette palette = Arrays.stream(MemberAppearance.Palette.values())
                .filter(candidate -> candidate.name().equals(entry.palette())).findFirst()
                .orElse(MemberAppearance.DEFAULT.palette());
        return new MemberAppearance(palette, entry.effects() == null || entry.effects());
    }

    /** Exported member id -> local member id, matched by username; unknown usernames are created locked. */
    private Map<String, String> mapMembers(Actor actor, MembersFile file, List<String> warnings) {
        Map<String, String> mapping = new LinkedHashMap<>();
        Map<String, MemberSummary> byUsername = new LinkedHashMap<>();
        members.listMembers(actor).forEach(m -> byUsername.put(m.username(), m));
        for (MemberEntry entry : file.members()) {
            MemberSummary local = byUsername.get(entry.username());
            if (local == null) {
                byte[] secret = new byte[24];
                random.nextBytes(secret);
                provisioning.ensureMember(new ProvisionMemberCommand(entry.username(), entry.displayName(),
                        Base64.getUrlEncoder().withoutPadding().encodeToString(secret),
                        entry.locale() == null ? ProductLocale.defaultLocale() : ProductLocale.valueOf(entry.locale()),
                        entry.kind() == null ? MemberKind.PERSON : MemberKind.valueOf(entry.kind()),
                        appearance(entry)));
                warnings.add("created member '" + entry.username()
                        + "' with an unknown random password; set a password through provisioning before use");
                byUsername.clear();
                members.listMembers(actor).forEach(m -> byUsername.put(m.username(), m));
                local = byUsername.get(entry.username());
            }
            mapping.put(entry.id(), local.id().toString());
        }
        return mapping;
    }

    /** @return imported, already present, classifications applied, classifications kept, analyses applied */
    private int[] restoreDocuments(Actor actor, ExportSource source, DocumentsFile file,
            Map<String, String> memberMapping, List<MemberId> persons, CatalogImportResult catalog,
            List<String> warnings) throws IOException {
        int imported = 0;
        int present = 0;
        int classificationsApplied = 0;
        int classificationsKept = 0;
        int analysesApplied = 0;
        for (DocumentEntry entry : file.documents()) {
            if (entry.originalPath() == null || !source.exists(entry.originalPath())) {
                warnings.add("document " + entry.id() + " skipped: original not included in export");
                continue;
            }
            List<SubjectView> subjects = LegacySubjects.expand(entry.subjects().stream().map(s -> new SubjectView(
                    s.kind(), s.memberId() == null ? null : mapMember(memberMapping, s.memberId()))).toList(),
                    persons);
            var result = importDocument.importDocument(actor, new ImportDocumentCommand(DocumentId.of(entry.id()),
                    entry.title(), entry.titleOrigin(), entry.originalFilename(), entry.contentType(),
                    mapMember(memberMapping, entry.uploadedBy()), entry.uploadedAt(), subjects,
                    entry.checksumSha256(), new ByteArrayInputStream(source.read(entry.originalPath())),
                    trash(entry.trash(), memberMapping)));
            if (result.outcome() == ImportDocument.ImportOutcome.IMPORTED) {
                imported++;
            } else {
                present++;
            }
            switch (restoreClassification(actor, entry, memberMapping, catalog)) {
                case APPLIED -> classificationsApplied++;
                case KEPT_EXISTING -> classificationsKept++;
                default -> {
                    // nothing to restore for this document
                }
            }
            if (entry.analysis() != null && importAnalysis.importAnalysis(actor, DocumentId.of(entry.id()),
                    ExportAnalysis.toRecord(entry.analysis(), memberMapping, catalog.labelIds(), persons,
                            subjects))
                    == ImportDocumentAnalysis.Outcome.APPLIED) {
                analysesApplied++;
            }
        }
        return new int[] {imported, present, classificationsApplied, classificationsKept, analysesApplied};
    }

    /**
     * The page ranges of documents created by the page editor, after every document is restored so that sources
     * listed later in the file are found; absent before schema 11. A document skipped above has none restored.
     *
     * @return imported, already present
     */
    private int[] restoreDerivations(Actor actor, DocumentsFile file, Map<String, String> memberMapping,
            List<String> warnings) {
        int imported = 0;
        int present = 0;
        for (DocumentEntry entry : file.documents()) {
            DerivationEntry derivation = entry.derivedFrom();
            if (derivation == null) {
                continue;
            }
            var result = derivations.importDerivation(actor, derivationRecord(entry.id(), derivation,
                    memberMapping));
            switch (result.outcome()) {
                case IMPORTED -> imported++;
                case ALREADY_PRESENT -> present++;
                case DOCUMENT_MISSING -> {
                    // already reported as a skipped document
                }
            }
            if (result.sourcesCleared() > 0) {
                warnings.add("document " + entry.id() + ": " + result.sourcesCleared()
                        + " page range(s) restored as from a deleted source, because the source is not stored here");
            }
        }
        return new int[] {imported, present};
    }

    private static DerivationRecord derivationRecord(String documentId, DerivationEntry entry,
            Map<String, String> memberMapping) {
        if (entry.pageEditId() == null || entry.createdBy() == null || entry.createdAt() == null
                || entry.ranges() == null) {
            throw new InvalidInputException("export.derivation.invalid",
                    "Incomplete derivation of document " + documentId);
        }
        return new DerivationRecord(DocumentId.of(documentId), UUID.fromString(entry.pageEditId()),
                mapOptionalMember(memberMapping, entry.createdBy()), entry.createdAt(), entry.ranges().stream()
                        .map(range -> new RangeRecord(range.firstPage(), range.lastPage(), range.sourceKind(),
                                range.sourceDocumentId() == null ? null : DocumentId.of(range.sourceDocumentId()),
                                range.firstSourcePage(), range.lastSourcePage(), range.rotation()))
                        .toList());
    }

    private ClassificationOutcome restoreClassification(Actor actor, DocumentEntry entry,
            Map<String, String> memberMapping, CatalogImportResult catalog) {
        ClassificationEntry classification = entry.classification();
        if (classification == null) {
            return ClassificationOutcome.NOTHING_TO_APPLY;
        }
        UUID folder = classification.folderId() == null ? null
                : catalog.folderIds().get(UUID.fromString(classification.folderId()));
        List<AssignmentImport> assignments = new ArrayList<>();
        for (var assignment : classification.labels()) {
            UUID label = catalog.labelIds().get(UUID.fromString(assignment.labelId()));
            if (label == null) {
                continue;
            }
            MemberId decidedBy = assignment.decidedBy() == null ? null
                    : MemberId.of(memberMapping.getOrDefault(assignment.decidedBy(), assignment.decidedBy()));
            assignments.add(new AssignmentImport(label, assignment.origin(), assignment.reviewState(),
                    assignment.confidence(), assignment.sourceRef(), decidedBy, assignment.decidedAt()));
        }
        return importCatalog.importClassification(actor, DocumentId.of(entry.id()), folder,
                placement(classification, memberMapping, catalog), assignments);
    }

    /**
     * The exported placement origin and a pending proposal, with folder ids mapped to local ones. Exports before
     * schema 4 carry neither; their placement counts as a member's decision, so no run refiles those documents.
     */
    private static PlacementImport placement(ClassificationEntry classification, Map<String, String> memberMapping,
            CatalogImportResult catalog) {
        ExportFormat.PlacementEntry placement = classification.placement();
        ExportFormat.PlacementSuggestionEntry exported = classification.suggestion();
        SuggestionImport suggestion = exported == null ? null : new SuggestionImport(
                mapFolder(exported.folderId(), catalog), mapFolder(exported.anchorFolderId(), catalog),
                exported.newPath() == null ? List.of() : exported.newPath(), exported.confidence(),
                exported.evidence(), exported.runRef(), exported.model(), exported.generation(),
                exported.createdAt());
        if (placement == null) {
            return classification.folderId() == null && suggestion == null ? null
                    : new PlacementImport("MANUAL", null, null, null, null, classification.folderId() != null,
                            suggestion);
        }
        MemberId by = placement.by() == null ? null
                : MemberId.of(memberMapping.getOrDefault(placement.by(), placement.by()));
        return new PlacementImport(placement.origin(), placement.confidence(), placement.sourceRef(), by,
                placement.at(), placement.byMember(), suggestion);
    }

    private static UUID mapFolder(String exportedId, CatalogImportResult catalog) {
        return exportedId == null ? null : catalog.folderIds().get(UUID.fromString(exportedId));
    }

    private int[] restoreTasks(Actor actor, TasksFile file, Map<String, String> memberMapping) {
        int imported = 0;
        int present = 0;
        for (TaskEntry entry : file.tasks()) {
            TaskExportRecord record = new TaskExportRecord(TaskId.of(entry.id()), entry.title(), entry.note(),
                    entry.ownerId() == null ? null : mapMember(memberMapping, entry.ownerId()), entry.status(),
                    date(entry.dueDate()), date(entry.plannedDate()),
                    entry.sourceDocumentId() == null ? null : DocumentId.of(entry.sourceDocumentId()),
                    entry.createdBy() == null ? actor.memberId() : mapMember(memberMapping, entry.createdBy()),
                    entry.createdAt(), entry.updatedAt(), entry.completedAt(),
                    entry.completedBy() == null ? null : mapMember(memberMapping, entry.completedBy()),
                    entry.contractId() == null ? null : dev.aegis.shared.ContractId.of(entry.contractId()),
                    entry.contractName(), date(entry.contractEnd()), date(entry.cancellationDeadline()),
                    date(entry.reminderDate()), entry.contractVersion(), trash(entry.trash(), memberMapping));
            if (importTask.importTask(actor, record) == ImportTask.ImportOutcome.IMPORTED) {
                imported++;
            } else {
                present++;
            }
        }
        return new int[] {imported, present};
    }

    /**
     * Contracts with their complete fact history; absent in exports before schema 5. Fact ids, review states and
     * the relationships between observations are restored as they were, so provenance survives the round trip.
     */
    private int[] restoreContracts(Actor actor, ExportSource source, Map<String, String> memberMapping)
            throws IOException {
        if (!source.exists(ExportFormat.CONTRACTS_JSON)) {
            return new int[] {0, 0};
        }
        int imported = 0;
        int present = 0;
        for (ContractEntry entry : read(source, ExportFormat.CONTRACTS_JSON, ContractsFile.class).contracts()) {
            List<FactExportRecord> facts = entry.facts().stream()
                    .map(fact -> new FactExportRecord(UUID.fromString(fact.id()), fact.type(), fact.valueKind(),
                            fact.value(), date(fact.validFrom()), date(fact.validUntil()), fact.reviewState(),
                            fact.origin(), fact.confidence(),
                            fact.sourceDocumentId() == null ? null : DocumentId.of(fact.sourceDocumentId()),
                            fact.sourcePage(), fact.sourceQuote(), fact.runRef(),
                            mapOptionalMember(memberMapping, fact.observedBy()), fact.observedAt(),
                            mapOptionalMember(memberMapping, fact.decidedBy()), fact.decidedAt(),
                            fact.supersededBy() == null ? null : UUID.fromString(fact.supersededBy()),
                            fact.corrects() == null ? null : UUID.fromString(fact.corrects()), fact.note()))
                    .toList();
            ContractExportRecord record = new ContractExportRecord(ContractId.of(entry.id()), entry.name(),
                    entry.status(), entry.createdBy() == null ? actor.memberId()
                            : mapMember(memberMapping, entry.createdBy()),
                    entry.createdAt(), entry.updatedAt(), facts, trash(entry.trash(), memberMapping));
            if (importContract.importContract(actor, record) == ImportContract.ImportOutcome.IMPORTED) {
                imported++;
            } else {
                present++;
            }
        }
        return new int[] {imported, present};
    }

    /** The exported trash record with the local member; files before schema 8 carry none. */
    private static Trash trash(ExportFormat.TrashEntry entry, Map<String, String> memberMapping) {
        if (entry == null || entry.trashedAt() == null) {
            return null;
        }
        if (entry.trashedBy() == null) {
            throw new InvalidInputException("export.trash.invalid", "A trashed record names nobody who trashed it");
        }
        MemberId by = mapOptionalMember(memberMapping, entry.trashedBy());
        Instant until = entry.restorableUntil() == null ? entry.trashedAt() : entry.restorableUntil();
        return new Trash(by, entry.trashedAt(), until);
    }

    private static MemberId mapMember(Map<String, String> mapping, String exportedId) {
        String local = mapping.get(exportedId);
        if (local == null) {
            throw new InvalidInputException("export.member.unknown", "Export references unknown member " + exportedId);
        }
        return MemberId.of(local);
    }

    /** An unknown member in an old export stays unmapped rather than failing the whole restore. */
    private static MemberId mapOptionalMember(Map<String, String> mapping, String exportedId) {
        if (exportedId == null) {
            return null;
        }
        String local = mapping.get(exportedId);
        return MemberId.of(local == null ? exportedId : local);
    }

    private <T> T read(ExportSource source, String path, Class<T> type) throws IOException {
        if (!source.exists(path)) {
            throw new InvalidInputException("export.file.missing", "Export file missing: " + path);
        }
        return serializer.fromJson(source.read(path), type);
    }

    private static LocalDate date(String value) {
        return value == null || value.isBlank() ? null : LocalDate.parse(value);
    }
}
