package dev.aegis.portability.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.aegis.documents.application.api.CatalogExport;
import dev.aegis.documents.application.api.DocumentExportQuery;
import dev.aegis.documents.application.api.DocumentExportRecord;
import dev.aegis.documents.application.api.OriginalContent;
import dev.aegis.documents.application.api.OriginalUnavailableException;
import dev.aegis.identity.application.api.MemberDirectory;
import dev.aegis.identity.application.api.MemberSummary;
import dev.aegis.planning.application.api.TaskExportRecord;
import dev.aegis.portability.application.ExportFormat.Manifest;
import dev.aegis.portability.application.api.ExportStatus;
import dev.aegis.portability.application.api.ExportSummary;
import dev.aegis.portability.application.api.RefreshExport;
import dev.aegis.portability.application.api.RefreshExport.Outcome;
import dev.aegis.portability.application.ports.ExportSerializer;
import dev.aegis.portability.application.ports.ExportStore;
import dev.aegis.review.application.api.ReviewPortability;
import dev.aegis.shared.Actor;
import dev.aegis.shared.DocumentId;
import dev.aegis.shared.HouseholdAccess;
import dev.aegis.shared.MemberId;
import dev.aegis.shared.NotAuthorizedException;
import dev.aegis.shared.ProductLocale;
import dev.aegis.shared.TaskId;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** The daily export: unchanged data writes nothing, changed data a new complete generation (ADR-0029). */
class RefreshExportTest {
    private static final Instant NOW = Instant.parse("2026-10-22T00:30:00Z");
    private final MemberId anna = MemberId.random();
    private final DocumentId invoice = DocumentId.random();
    private final byte[] pdf = "%PDF-invoice".getBytes(StandardCharsets.US_ASCII);
    private final List<TaskExportRecord> tasks = new ArrayList<>();
    private boolean originalAvailable = true;
    private boolean databaseDown;
    private final MutableClock clock = new MutableClock(NOW);
    private final ManifestKeepingSerializer serializer = new ManifestKeepingSerializer();
    private final GenerationStore store = new GenerationStore(serializer);

    @Test
    void unchangedDataWritesNoNewGenerationButConfirmsTheLatestOne() {
        ExportService service = service(14);

        RefreshExport.Result first = service.refresh(RefreshExport.EXPORTER);
        clock.advance(Duration.ofDays(1));
        RefreshExport.Result second = service.refresh(RefreshExport.EXPORTER);

        assertThat(first.outcome()).isEqualTo(Outcome.CREATED);
        assertThat(first.current().trigger()).isEqualTo(ExportSummary.SCHEDULED);
        assertThat(first.current().dataFingerprint()).hasSize(64);
        assertThat(second.outcome()).isEqualTo(Outcome.UNCHANGED);
        assertThat(second.current().generation()).isEqualTo(first.current().generation());
        assertThat(store.generations).hasSize(1);
        assertThat(store.run).isEqualTo(new ExportStore.ScheduledRun(NOW.plus(Duration.ofDays(1)), null));
    }

    @Test
    void changedDataWritesANewGeneration() {
        ExportService service = service(14);
        service.refresh(RefreshExport.EXPORTER);

        tasks.add(task());
        clock.advance(Duration.ofDays(1));
        RefreshExport.Result result = service.refresh(RefreshExport.EXPORTER);

        assertThat(result.outcome()).isEqualTo(Outcome.CREATED);
        assertThat(result.current().taskCount()).isEqualTo(1);
        assertThat(store.generations).hasSize(2);
    }

    @Test
    void aManualExportWithTheSameDataAlsoCountsAsCurrent() {
        ExportService service = service(14);
        ExportSummary manual = service.export(new Actor(anna));

        RefreshExport.Result result = service.refresh(RefreshExport.EXPORTER);

        assertThat(manual.trigger()).isEqualTo(ExportSummary.MANUAL);
        assertThat(result.outcome()).isEqualTo(Outcome.UNCHANGED);
        assertThat(result.current().generation()).isEqualTo(manual.generation());
    }

    @Test
    void anOriginalMissingFromTheLatestGenerationIsExportedOnceItIsBack() {
        ExportService service = service(14);
        originalAvailable = false;
        RefreshExport.Result incomplete = service.refresh(RefreshExport.EXPORTER);
        clock.advance(Duration.ofDays(1));
        RefreshExport.Result stillMissing = service.refresh(RefreshExport.EXPORTER);

        originalAvailable = true;
        clock.advance(Duration.ofDays(1));
        RefreshExport.Result recovered = service.refresh(RefreshExport.EXPORTER);

        assertThat(incomplete.current().missingOriginals()).containsExactly(invoice.toString());
        assertThat(stillMissing.outcome()).isEqualTo(Outcome.UNCHANGED);
        assertThat(recovered.outcome()).isEqualTo(Outcome.CREATED);
        assertThat(recovered.current().missingOriginals()).isEmpty();
    }

    @Test
    void retentionRemovesOnlyOlderScheduledGenerations() {
        ExportService service = service(2);
        ExportSummary manual = service.export(new Actor(anna));
        List<String> scheduled = new ArrayList<>();
        for (int day = 0; day < 4; day++) {
            tasks.add(task());
            clock.advance(Duration.ofDays(1));
            scheduled.add(service.refresh(RefreshExport.EXPORTER).current().generation());
        }

        assertThat(store.generations.keySet()).containsExactlyInAnyOrder(manual.generation(), scheduled.get(2),
                scheduled.get(3));
    }

    @Test
    void aFailedRunPublishesNothingAndIsRecordedForTheStatusPage() {
        ExportService service = service(14);
        service.refresh(RefreshExport.EXPORTER);
        String good = store.latest.generation();

        databaseDown = true;
        clock.advance(Duration.ofDays(1));
        assertThatThrownBy(() -> service.refresh(RefreshExport.EXPORTER)).isInstanceOf(IllegalStateException.class);

        ExportStatus status = service.status(new Actor(anna));
        assertThat(status.latest().generation()).isEqualTo(good);
        assertThat(status.lastCheckedAt()).isEqualTo(NOW);
        assertThat(status.lastFailedAt()).isEqualTo(NOW.plus(Duration.ofDays(1)));
    }

    @Test
    void onlyTheExporterRunsTheDailyExport() {
        ExportService service = service(14);

        assertThatThrownBy(() -> service.refresh(new Actor(anna))).isInstanceOf(NotAuthorizedException.class);
        assertThatThrownBy(() -> service.refresh(Actor.householdProcess("review-reconciler")))
                .isInstanceOf(NotAuthorizedException.class);
        assertThat(store.generations).isEmpty();
    }

    private ExportService service(int retention) {
        HouseholdAccess access = new HouseholdAccess() {
            @Override
            public void requireMember(Actor actor) {
                if (actor.isHouseholdProcess()) {
                    throw new NotAuthorizedException("not a member");
                }
            }

            @Override
            public void requireReader(Actor actor) {
            }
        };
        ReviewPortability reviews = new ReviewPortability() {
            @Override
            public List<Record> exportAll(Actor actor) {
                return List.of();
            }

            @Override
            public void importAll(Actor actor, List<Record> records) {
            }
        };
        return new ExportService(documents(), new dev.aegis.documents.application.api.DerivationPortability() {
            @Override
            public List<DerivationRecord> exportAll(Actor actor) {
                return List.of();
            }

            @Override
            public ImportResult importDerivation(Actor actor, DerivationRecord record) {
                throw new UnsupportedOperationException();
            }
        }, actor -> List.copyOf(tasks), actor -> List.of(), reviews, members(), store, serializer, access, clock,
                "test", retention);
    }

    private DocumentExportQuery documents() {
        return new DocumentExportQuery() {
            @Override
            public List<DocumentExportRecord> exportAll(Actor actor) {
                if (databaseDown) {
                    throw new IllegalStateException("database gone");
                }
                return List.of(new DocumentExportRecord(invoice, "Rechnung", "MANUAL", "rechnung.pdf",
                        "Rechnung_" + invoice.toString().substring(0, 8) + ".pdf", "application/pdf", pdf.length,
                        ExportService.sha256(pdf), anna, NOW, List.of(), "ARCHIVED", 3, NOW, null, null, null));
            }

            @Override
            public OriginalContent originalForExport(Actor actor, DocumentId id) {
                if (!originalAvailable) {
                    throw new OriginalUnavailableException("archive down");
                }
                return new OriginalContent("rechnung.pdf", "application/pdf", pdf, OriginalContent.SOURCE_ARCHIVE);
            }

            @Override
            public CatalogExport exportCatalog(Actor actor) {
                return new CatalogExport(List.of(), List.of());
            }
        };
    }

    private MemberDirectory members() {
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

    private TaskExportRecord task() {
        return new TaskExportRecord(TaskId.random(), "Zahlen", "", anna, "OPEN", null, null, invoice, anna, NOW, NOW,
                null, null, null, null, null, null, null, null, null);
    }

    /** Serializes as text and keeps the last manifest, so the store can read it back like a real one. */
    static final class ManifestKeepingSerializer implements ExportSerializer {
        Manifest manifest;

        @Override
        public byte[] toJson(Object value) {
            if (value instanceof Manifest written) {
                manifest = written;
            }
            return value.toString().getBytes(StandardCharsets.UTF_8);
        }

        @Override
        public <T> T fromJson(byte[] json, Class<T> type) {
            throw new UnsupportedOperationException();
        }
    }

    /** Published generations in memory, newest first, like the filesystem store. */
    static final class GenerationStore implements ExportStore {
        final Map<String, ExportSummary> generations = Collections.synchronizedMap(new LinkedHashMap<>());
        private final ManifestKeepingSerializer serializer;
        ExportSummary latest;
        ScheduledRun run;

        GenerationStore(ManifestKeepingSerializer serializer) {
            this.serializer = serializer;
        }

        @Override
        public ExportStaging beginGeneration(String generation) {
            return new ExportStaging() {
                @Override
                public void write(String relativePath, byte[] content) {
                }

                @Override
                public String publish() {
                    Manifest m = serializer.manifest;
                    latest = new ExportSummary(m.generation(), m.exportedAt(), m.documentCount(), m.taskCount(),
                            m.memberCount(), m.contractCount(), m.missingOriginals(), "memory://" + generation,
                            m.trigger(), m.dataFingerprint());
                    generations.put(generation, latest);
                    return latest.location();
                }

                @Override
                public void discard() {
                }
            };
        }

        @Override
        public List<ExportSummary> listGenerations() {
            List<ExportSummary> newestFirst = new ArrayList<>(generations.values());
            Collections.reverse(newestFirst);
            return newestFirst;
        }

        @Override
        public Optional<ExportSummary> latest() {
            return Optional.ofNullable(latest);
        }

        @Override
        public void deleteGeneration(String generation) {
            if (latest != null && latest.generation().equals(generation)) {
                throw new IllegalStateException("latest");
            }
            generations.remove(generation);
        }

        @Override
        public Optional<ScheduledRun> scheduledRun() {
            return Optional.ofNullable(run);
        }

        @Override
        public void recordScheduledRun(ScheduledRun newRun) {
            run = newRun;
        }
    }

    static final class MutableClock extends Clock {
        private Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
