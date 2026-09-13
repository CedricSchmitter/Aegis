package dev.aegis.portability.application;

import static org.assertj.core.api.Assertions.assertThat;

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
        DocumentExportQuery documents = new DocumentExportQuery() {
            @Override
            public List<DocumentExportRecord> exportAll(Actor actor) {
                return List.of(new DocumentExportRecord(contract, "Mietvertrag Müller", "vertrag.pdf",
                        "application/pdf", pdf.length, ExportService.sha256(pdf), anna, NOW,
                        List.of(new SubjectView("HOUSE", null)), "ARCHIVED", 7, NOW), new DocumentExportRecord(lost,
                        "Verloren", "lost.pdf", "application/pdf", 3, "a".repeat(64), anna, NOW, List.of(),
                        "FAILED", null, null));
            }

            @Override
            public OriginalContent originalForExport(Actor actor, DocumentId id) {
                if (id.equals(contract)) {
                    return new OriginalContent("vertrag.pdf", "application/pdf", pdf, OriginalContent.SOURCE_ARCHIVE);
                }
                throw new OriginalUnavailableException("gone");
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

        String originalPath = ExportFormat.ORIGINALS_DIR + "2026-09-13_mietvertrag-mueller_"
                + contract.toString().substring(0, 8) + ".pdf";
        assertThat(store.published).isTrue();
        assertThat(store.staged).containsKeys(originalPath, ExportFormat.DOCUMENTS_JSON, ExportFormat.TASKS_JSON,
                ExportFormat.MEMBERS_JSON, ExportFormat.DOCUMENTS_CSV, ExportFormat.TASKS_CSV, ExportFormat.INDEX_MD,
                ExportFormat.MANIFEST);
        assertThat(store.staged.get(originalPath)).isEqualTo(pdf);
        assertThat(summary.documentCount()).isEqualTo(2);
        assertThat(summary.taskCount()).isEqualTo(1);
        assertThat(summary.missingOriginals()).containsExactly(lost.toString());
        String index = new String(store.staged.get(ExportFormat.INDEX_MD), StandardCharsets.UTF_8);
        assertThat(index).contains("[Mietvertrag Müller](" + originalPath + ")")
                .contains("source [Mietvertrag Müller](" + originalPath + ")").contains("Verloren (original not included)");
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
    void csvQuotingAndSlugsAreStable() {
        assertThat(Csv.quote("a,b")).isEqualTo("\"a,b\"");
        assertThat(Csv.quote("say \"hi\"")).isEqualTo("\"say \"\"hi\"\"\"");
        assertThat(Slugs.slug("Straße & Öl / Über 2026!", 60)).isEqualTo("strasse-oel-ueber-2026");
        assertThat(Slugs.slug("   ", 10)).isEqualTo("document");
    }
}
