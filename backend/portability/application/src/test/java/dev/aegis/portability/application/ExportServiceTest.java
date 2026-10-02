package dev.aegis.portability.application;

import static org.assertj.core.api.Assertions.assertThat;

import dev.aegis.contracts.application.api.ContractExportQuery;
import dev.aegis.contracts.application.api.ContractExportRecord;
import dev.aegis.contracts.application.api.ContractExportRecord.FactExportRecord;
import dev.aegis.documents.application.api.CatalogExport;
import dev.aegis.documents.application.api.DerivationPortability;
import dev.aegis.documents.application.api.DerivationPortability.DerivationRecord;
import dev.aegis.documents.application.api.DerivationPortability.RangeRecord;
import dev.aegis.documents.application.api.DocumentExportQuery;
import dev.aegis.documents.application.api.DocumentExportRecord;
import dev.aegis.documents.application.api.OriginalContent;
import dev.aegis.documents.application.api.OriginalUnavailableException;
import dev.aegis.documents.application.api.SubjectView;
import dev.aegis.identity.application.api.MemberDirectory;
import dev.aegis.identity.application.api.MemberSummary;
import dev.aegis.planning.application.api.TaskExportQuery;
import dev.aegis.planning.application.api.TaskExportRecord;
import dev.aegis.portability.application.api.ExportSummary;
import dev.aegis.portability.application.ports.ExportSerializer;
import dev.aegis.portability.application.ports.ExportStore;
import dev.aegis.review.application.api.ReviewPortability;
import dev.aegis.shared.Actor;
import dev.aegis.shared.ContractId;
import dev.aegis.shared.DocumentId;
import dev.aegis.shared.MemberId;
import dev.aegis.shared.ProductLocale;
import dev.aegis.shared.TaskId;
import dev.aegis.shared.Trash;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class ExportServiceTest {
    private static final Instant NOW = Instant.parse("2026-09-13T10:00:00Z");
    private final MemberId anna = MemberId.random();
    private final DocumentId contract = DocumentId.random();
    private final DocumentId lost = DocumentId.random();
    private final java.util.UUID house = java.util.UUID.randomUUID();
    private final java.util.UUID invoiceLabel = java.util.UUID.randomUUID();
    private final java.util.UUID telekomLabel = java.util.UUID.randomUUID();
    private final ContractId rental = ContractId.random();
    private final java.util.UUID endDateFact = java.util.UUID.randomUUID();
    private final java.util.UUID noticeFact = java.util.UUID.randomUUID();

    /** Records writes and publication; never touches a filesystem. */
    static final class MemoryStore implements ExportStore {
        final Map<String, byte[]> staged = new LinkedHashMap<>();
        boolean published;
        boolean discarded;

        @Override
        public ExportStaging beginGeneration(String generation) {
            return new ExportStaging() {
                @Override
                public void write(String relativePath, byte[] content) {
                    staged.put(relativePath, content);
                }

                @Override
                public String publish() {
                    published = true;
                    return "memory://" + generation;
                }

                @Override
                public void discard() {
                    discarded = true;
                }
            };
        }

        @Override
        public List<ExportSummary> listGenerations() {
            return List.of();
        }

        @Override
        public Optional<ExportSummary> latest() {
            return Optional.empty();
        }
    }

    /** Serializes as readable key=value lines; the shape is irrelevant to what this test checks. */
    static final class StubSerializer implements ExportSerializer {
        @Override
        public byte[] toJson(Object value) {
            return value.toString().getBytes(StandardCharsets.UTF_8);
        }

        @Override
        public <T> T fromJson(byte[] json, Class<T> type) {
            throw new UnsupportedOperationException();
        }
    }

    @Test
    void writesOriginalsDataReadableFilesAndManifestWithRelativeLinks() {
        byte[] pdf = "%PDF-contract".getBytes(StandardCharsets.US_ASCII);
        String contractName = "2026-09-13_Mieter-Verein_Vertrag_Miete_" + contract.toString().substring(0, 8)
                + ".pdf";
        DocumentExportQuery documents = new DocumentExportQuery() {
            @Override
            public List<DocumentExportRecord> exportAll(Actor actor) {
                return List.of(new DocumentExportRecord(contract, "Mietvertrag Müller", "MANUAL", "vertrag.pdf",
                        contractName, "application/pdf", pdf.length, ExportService.sha256(pdf), anna, NOW,
                        List.of(new SubjectView("HOUSE", null)), "ARCHIVED", 7, NOW,
                        new DocumentExportRecord.Classification(house, List.of("Haus", "Rechnungen"),
                                List.of(new DocumentExportRecord.Assignment(telekomLabel, "ORGANIZATION", "Telekom",
                                        "MANUAL", "CONFIRMED", null, null, anna, NOW),
                                        new DocumentExportRecord.Assignment(invoiceLabel, "DOCUMENT_TYPE",
                                                "Invoice", "AUTOMATIC", "SUGGESTED", 0.4, "run:1", null, NOW)),
                                new DocumentExportRecord.Placement("MANUAL", null, null, anna, NOW, true), null),
                        null, null),
                        new DocumentExportRecord(lost, "Verloren", "FILENAME", "lost.pdf", "Verloren_"
                                + lost.toString().substring(0, 8) + ".pdf", "application/pdf", 3, "a".repeat(64),
                                anna, NOW, List.of(), "FAILED", null, null, null, null,
                                new Trash(anna, NOW, NOW.plus(Duration.ofDays(30)))));
            }

            @Override
            public OriginalContent originalForExport(Actor actor, DocumentId id) {
                if (id.equals(contract)) {
                    return new OriginalContent("vertrag.pdf", "application/pdf", pdf, OriginalContent.SOURCE_ARCHIVE);
                }
                throw new OriginalUnavailableException("gone");
            }

            @Override
            public CatalogExport exportCatalog(Actor actor) {
                return new CatalogExport(List.of(new CatalogExport.LabelEntry(invoiceLabel, "DOCUMENT_TYPE",
                        "invoice", null, "Invoice", List.of("Rechnung", "Invoice"), "ACTIVE", null),
                        new CatalogExport.LabelEntry(telekomLabel, "ORGANIZATION", null, "Telekom", "Telekom",
                                List.of("Deutsche Telekom"), "ACTIVE", null)),
                        List.of(new CatalogExport.FolderEntry(house, null, "Haus", List.of("Haus"), List.of(),
                                List.of(new SubjectView("HOUSE", null)))));
            }
        };
        TaskExportQuery tasks = actor -> List.of(new TaskExportRecord(TaskId.random(), "Kündigen", "", anna, "OPEN",
                LocalDate.of(2026, 12, 31), null, contract, anna, NOW, NOW, null, null,
                null, null, null, null, null, null, null));
        ContractExportQuery contracts = actor -> List.of(new ContractExportRecord(rental, "Mietvertrag",
                "ACTIVE", anna, NOW, NOW, List.of(
                        new FactExportRecord(endDateFact, "END_DATE", "DATE", "2028-01-01", null, null,
                                "CONFIRMED", "MANUAL", null, contract, 2, "Vertragsende: 01.01.2028", null, anna,
                                NOW, anna, NOW, null, null, ""),
                        new FactExportRecord(noticeFact, "NOTICE_PERIOD", "PERIOD", "P3M", null, null, "EXTRACTED",
                                "AUTOMATIC", 0.8, contract, null, "Kündigungsfrist drei Monate", "run:2", null,
                                NOW, null, null, null, null, "")), null));
        MemberDirectory members = new MemberDirectory() {
            @Override
            public List<MemberSummary> listMembers(Actor actor) {
                return List.of(new MemberSummary(anna, "anna", "Anna", ProductLocale.DE, true));
            }

            @Override
            public Optional<MemberSummary> findMember(MemberId id) {
                return Optional.empty();
            }
        };
        MemoryStore store = new MemoryStore();
        ExportService service = new ExportService(documents, tasks, contracts, members, store,
                new StubSerializer(), actor -> {
                }, Clock.fixed(NOW, ZoneOffset.UTC), "test");

        ExportSummary summary = service.export(new Actor(anna));
        assertThat(service.export(new Actor(anna)).generation()).isNotEqualTo(summary.generation());

        // The original is exported unchanged under the friendly filename; the upload name stays in the metadata.
        String originalPath = ExportFormat.ORIGINALS_DIR + contractName;
        assertThat(store.published).isTrue();
        assertThat(store.staged).containsKeys(originalPath, ExportFormat.DOCUMENTS_JSON, ExportFormat.TASKS_JSON,
                ExportFormat.MEMBERS_JSON, ExportFormat.CATALOG_JSON, ExportFormat.CONTRACTS_JSON,
                ExportFormat.DOCUMENTS_CSV, ExportFormat.TASKS_CSV, ExportFormat.LABELS_CSV,
                ExportFormat.CONTRACTS_CSV, ExportFormat.INDEX_MD, ExportFormat.MANIFEST);
        assertThat(store.staged.get(originalPath)).isEqualTo(pdf);
        assertThat(summary.documentCount()).isEqualTo(2);
        assertThat(summary.taskCount()).isEqualTo(1);
        assertThat(summary.missingOriginals()).containsExactly(lost.toString());
        String index = new String(store.staged.get(ExportFormat.INDEX_MD), StandardCharsets.UTF_8);
        assertThat(index).contains("[Mietvertrag Müller](" + originalPath + ")")
                .contains("source [Mietvertrag Müller](" + originalPath + ")")
                .contains("Verloren (original not included)")
                .contains("state FAILED, in the trash since " + NOW).contains("### Haus / Rechnungen")
                .contains("### (unsorted)").contains("labels Telekom");
        String documentsCsv = new String(store.staged.get(ExportFormat.DOCUMENTS_CSV), StandardCharsets.UTF_8);
        assertThat(documentsCsv).contains("id,title,originalFilename,filename,")
                .contains("vertrag.pdf," + contractName)
                .contains("folder,documentType,organizations,topics,tags,documentDate,analysisStatus,trashedAt")
                .contains("NOT_REQUESTED," + NOW)
                .contains("Haus / Rechnungen,,Telekom,,");
        String labelsCsv = new String(store.staged.get(ExportFormat.LABELS_CSV), StandardCharsets.UTF_8);
        assertThat(labelsCsv).contains("DOCUMENT_TYPE,Invoice,invoice,Rechnung; Invoice,ACTIVE,,0")
                .contains("ORGANIZATION,Telekom,,Deutsche Telekom,ACTIVE,,0");
        String catalog = new String(store.staged.get(ExportFormat.CATALOG_JSON), StandardCharsets.UTF_8);
        assertThat(catalog).contains(telekomLabel.toString()).contains("Haus");
        String manifest = new String(store.staged.get(ExportFormat.MANIFEST), StandardCharsets.UTF_8);
        assertThat(manifest).contains(originalPath + "=" + ExportService.sha256(pdf));
        String csv = new String(store.staged.get(ExportFormat.TASKS_CSV), StandardCharsets.UTF_8);
        assertThat(csv).contains("Kündigen,,Anna,OPEN,2026-12-31,," + contract + ",Mietvertrag Müller");
        // Every observation is one readable row, with its review state, its source document and its page.
        assertThat(summary.contractCount()).isEqualTo(1);
        String contractsCsv = new String(store.staged.get(ExportFormat.CONTRACTS_CSV), StandardCharsets.UTF_8);
        assertThat(contractsCsv)
                .contains("contractId,contract,contractStatus,contractTrashedAt,factId,factType,value,validFrom,"
                        + "validUntil,reviewState,origin,confidence")
                .contains("END_DATE,2028-01-01,,,CONFIRMED,MANUAL,," + contract + ",Mietvertrag Müller,2,")
                .contains("NOTICE_PERIOD,P3M,,,EXTRACTED,AUTOMATIC,0.8,");
        // The readable index lists confirmed facts only; a proposal is not household knowledge yet.
        assertThat(index).contains("## Contracts (1)").contains("Mietvertrag (ACTIVE), 1 confirmed of 2")
                .contains("END_DATE: 2028-01-01").doesNotContain("NOTICE_PERIOD: P3M");
    }

    @Test
    void failedGenerationIsDiscardedAndNeverPublished() {
        DocumentExportQuery failing = new DocumentExportQuery() {
            @Override
            public List<DocumentExportRecord> exportAll(Actor actor) {
                throw new IllegalStateException("database gone");
            }

            @Override
            public OriginalContent originalForExport(Actor actor, DocumentId id) {
                throw new UnsupportedOperationException();
            }

            @Override
            public CatalogExport exportCatalog(Actor actor) {
                return new CatalogExport(List.of(), List.of());
            }
        };
        MemoryStore store = new MemoryStore();
        ExportService service = new ExportService(failing, actor -> List.of(), actor -> List.of(),
                new MemberDirectory() {
            @Override
            public List<MemberSummary> listMembers(Actor actor) {
                return List.of();
            }

            @Override
            public Optional<MemberSummary> findMember(MemberId id) {
                return Optional.empty();
            }
        }, store, new StubSerializer(), actor -> {
        }, Clock.fixed(NOW, ZoneOffset.UTC), "test");

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.export(new Actor(anna)))
                .isInstanceOf(IllegalStateException.class);

        assertThat(store.discarded).isTrue();
        assertThat(store.published).isFalse();
    }

    @Test
    void csvQuotingIsStable() {
        assertThat(Csv.quote("a,b")).isEqualTo("\"a,b\"");
        assertThat(Csv.quote("say \"hi\"")).isEqualTo("\"say \"\"hi\"\"\"");
    }

    @Test
    void collidingFriendlyFilenamesUseTheFullIdSoNoOriginalOverwritesAnother() {
        DocumentId first = DocumentId.of("aaaaaaaa-0000-4000-8000-000000000001");
        DocumentId second = DocumentId.of("aaaaaaaa-0000-4000-8000-000000000002");
        DocumentId third = DocumentId.of("bbbbbbbb-0000-4000-8000-000000000003");
        List<DocumentExportRecord> records = List.of(record(first, "Rechnung_aaaaaaaa.pdf"),
                record(second, "RECHNUNG_aaaaaaaa.pdf"), record(third, "Rechnung_bbbbbbbb.pdf"));

        Map<String, String> names = ExportService.exportFilenames(records);

        assertThat(names.get(first.toString())).isEqualTo("Rechnung_aaaaaaaa000040008000000000000001.pdf");
        assertThat(names.get(second.toString())).isEqualTo("RECHNUNG_aaaaaaaa000040008000000000000002.pdf");
        assertThat(names.get(third.toString())).isEqualTo("Rechnung_bbbbbbbb.pdf");
        assertThat(names.values()).doesNotHaveDuplicates();
    }

    /** Schema 11 carries where the pages of a page-editor document came from, by Aegis document id. */
    @Test
    void exportsWhereThePagesOfAPageEditorDocumentCameFromInDataAndReadableFiles() {
        DocumentId source = DocumentId.of("cccccccc-0000-4000-8000-000000000001");
        DocumentId result = DocumentId.of("dddddddd-0000-4000-8000-000000000002");
        DocumentId deleted = DocumentId.of("eeeeeeee-0000-4000-8000-000000000003");
        java.util.UUID pageEdit = java.util.UUID.fromString("ffffffff-0000-4000-8000-000000000004");
        List<DocumentExportRecord> records = List.of(
                new DocumentExportRecord(source, "Stromrechnung", "FILENAME", "strom.pdf", "Strom_cccccccc.pdf",
                        "application/pdf", 1, "b".repeat(64), anna, NOW, List.of(), "ARCHIVED", 1, NOW, null, null,
                        null),
                new DocumentExportRecord(result, "Stromrechnung, Teil 2", "FILENAME", "teil2.pdf",
                        "Teil2_dddddddd.pdf", "application/pdf", 1, "c".repeat(64), anna, NOW, List.of(),
                        "ARCHIVED", 2, NOW, null, null, null));
        DocumentExportQuery documents = new DocumentExportQuery() {
            @Override
            public List<DocumentExportRecord> exportAll(Actor actor) {
                return records;
            }

            @Override
            public OriginalContent originalForExport(Actor actor, DocumentId id) {
                throw new OriginalUnavailableException("not needed here");
            }

            @Override
            public CatalogExport exportCatalog(Actor actor) {
                return new CatalogExport(List.of(), List.of());
            }
        };
        DerivationPortability derivations = new DerivationPortability() {
            @Override
            public List<DerivationRecord> exportAll(Actor actor) {
                return List.of(new DerivationRecord(result, pageEdit, anna, NOW, List.of(
                        new RangeRecord(1, 2, "DOCUMENT", source, 3, 4, 90),
                        new RangeRecord(3, 3, "SCAN", null, 1, 1, 0),
                        new RangeRecord(4, 4, "DOCUMENT", null, 7, 7, 0))));
            }

            @Override
            public ImportResult importDerivation(Actor actor, DerivationRecord record) {
                throw new UnsupportedOperationException();
            }
        };
        MemoryStore store = new MemoryStore();
        ExportService service = new ExportService(documents, derivations, actor -> List.of(), actor -> List.of(),
                new ReviewPortability() {
                    @Override
                    public List<Record> exportAll(Actor actor) {
                        return List.of();
                    }

                    @Override
                    public void importAll(Actor actor, List<Record> records) {
                    }
                }, onlyAnna(), store, new StubSerializer(), actor -> {
                }, Clock.fixed(NOW, ZoneOffset.UTC), "test");

        service.export(new Actor(anna));

        String data = new String(store.staged.get(ExportFormat.DOCUMENTS_JSON), StandardCharsets.UTF_8);
        assertThat(data).contains("schemaVersion=11")
                .contains("derivedFrom=DerivationEntry[pageEditId=" + pageEdit + ", createdBy=" + anna
                        + ", createdAt=" + NOW + ", ranges=[DerivationRangeEntry[firstPage=1, lastPage=2, "
                        + "sourceKind=DOCUMENT, sourceDocumentId=" + source + ", firstSourcePage=3, "
                        + "lastSourcePage=4, rotation=90], DerivationRangeEntry[firstPage=3, lastPage=3, "
                        + "sourceKind=SCAN, sourceDocumentId=null")
                .contains("sourceKind=DOCUMENT, sourceDocumentId=null, firstSourcePage=7")
                .contains("trash=null, derivedFrom=null]")
                .doesNotContain(deleted.toString());
        String summary = "pages 1-2: pages 3-4 of Stromrechnung (turned 90 degrees); page 3: scanned page 1; "
                + "page 4: page 7 of a deleted document";
        String documentsCsv = new String(store.staged.get(ExportFormat.DOCUMENTS_CSV), StandardCharsets.UTF_8);
        assertThat(documentsCsv).contains("analysisStatus,trashedAt,derivedFrom")
                .contains("NOT_REQUESTED,," + Csv.quote(summary));
        String index = new String(store.staged.get(ExportFormat.INDEX_MD), StandardCharsets.UTF_8);
        assertThat(index).contains("Stromrechnung, Teil 2 (original not included)")
                .contains(", created from " + summary);
    }

    private MemberDirectory onlyAnna() {
        return new MemberDirectory() {
            @Override
            public List<MemberSummary> listMembers(Actor actor) {
                return List.of(new MemberSummary(anna, "anna", "Anna", ProductLocale.DE, true));
            }

            @Override
            public Optional<MemberSummary> findMember(MemberId id) {
                return Optional.empty();
            }
        };
    }

    private static DocumentExportRecord record(DocumentId id, String filename) {
        return new DocumentExportRecord(id, "Rechnung", "FILENAME", "scan.pdf", filename, "application/pdf", 1,
                "b".repeat(64), MemberId.random(), NOW, List.of(), "ARCHIVED", 1, NOW, null, null, null);
    }
}
