package dev.aegis.portability.application;

import static org.assertj.core.api.Assertions.assertThat;

import dev.aegis.documents.application.api.CatalogExport;
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
import dev.aegis.shared.Actor;
import dev.aegis.shared.DocumentId;
import dev.aegis.shared.MemberId;
import dev.aegis.shared.ProductLocale;
import dev.aegis.shared.TaskId;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
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
                        null),
                        new DocumentExportRecord(lost, "Verloren", "FILENAME", "lost.pdf", "Verloren_"
                                + lost.toString().substring(0, 8) + ".pdf", "application/pdf", 3, "a".repeat(64),
                                anna, NOW, List.of(), "FAILED", null, null, null, null));
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
                LocalDate.of(2026, 12, 31), null, contract, anna, NOW, NOW, null, null));
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
        ExportService service = new ExportService(documents, tasks, members, store, new StubSerializer(), actor -> {
        }, Clock.fixed(NOW, ZoneOffset.UTC), "test");

        ExportSummary summary = service.export(new Actor(anna));
        assertThat(service.export(new Actor(anna)).generation()).isNotEqualTo(summary.generation());

        // The original is exported unchanged under the friendly filename; the upload name stays in the metadata.
        String originalPath = ExportFormat.ORIGINALS_DIR + contractName;
        assertThat(store.published).isTrue();
        assertThat(store.staged).containsKeys(originalPath, ExportFormat.DOCUMENTS_JSON, ExportFormat.TASKS_JSON,
                ExportFormat.MEMBERS_JSON, ExportFormat.CATALOG_JSON, ExportFormat.DOCUMENTS_CSV,
                ExportFormat.TASKS_CSV, ExportFormat.LABELS_CSV, ExportFormat.INDEX_MD, ExportFormat.MANIFEST);
        assertThat(store.staged.get(originalPath)).isEqualTo(pdf);
        assertThat(summary.documentCount()).isEqualTo(2);
        assertThat(summary.taskCount()).isEqualTo(1);
        assertThat(summary.missingOriginals()).containsExactly(lost.toString());
        String index = new String(store.staged.get(ExportFormat.INDEX_MD), StandardCharsets.UTF_8);
        assertThat(index).contains("[Mietvertrag Müller](" + originalPath + ")")
                .contains("source [Mietvertrag Müller](" + originalPath + ")")
                .contains("Verloren (original not included)").contains("### Haus / Rechnungen")
                .contains("### (unsorted)").contains("labels Telekom");
        String documentsCsv = new String(store.staged.get(ExportFormat.DOCUMENTS_CSV), StandardCharsets.UTF_8);
        assertThat(documentsCsv).contains("id,title,originalFilename,filename,")
                .contains("vertrag.pdf," + contractName)
                .contains("folder,documentType,organizations,topics,tags,documentDate,analysisStatus")
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
        ExportService service = new ExportService(failing, actor -> List.of(), new MemberDirectory() {
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

    private static DocumentExportRecord record(DocumentId id, String filename) {
        return new DocumentExportRecord(id, "Rechnung", "FILENAME", "scan.pdf", filename, "application/pdf", 1,
                "b".repeat(64), MemberId.random(), NOW, List.of(), "ARCHIVED", 1, NOW, null, null);
    }
}
