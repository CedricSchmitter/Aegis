package dev.aegis.documents.application;

import dev.aegis.documents.application.api.OriginalContent;
import dev.aegis.documents.application.ports.AnalysisRuns;
import dev.aegis.documents.application.ports.ArchiveUnavailableException;
import dev.aegis.documents.domain.naming.BuiltinLabelNames;
import java.time.ZoneId;
import java.util.Collection;
import java.util.UUID;
import dev.aegis.documents.application.ports.DocumentEvents;
import dev.aegis.documents.application.ports.PaperlessArchive;
import dev.aegis.documents.application.ports.ProcessingScheduler;
import dev.aegis.shared.Actor;
import dev.aegis.shared.DocumentId;
import dev.aegis.shared.HouseholdAccess;
import dev.aegis.shared.MemberId;
import dev.aegis.shared.NotAuthorizedException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** Small hand-written doubles keep application tests fast and explicit. */
final class TestDoubles {
    private TestDoubles() {
    }

    static final class RecordingScheduler implements ProcessingScheduler {
        final Set<DocumentId> scheduled = new LinkedHashSet<>();
        final Map<DocumentId, Instant> next = new HashMap<>();

        @Override
        public void scheduleArchival(DocumentId id) {
            scheduled.add(id);
            next.putIfAbsent(id, Instant.parse("2026-09-13T10:00:05Z"));
        }

        @Override
        public Optional<Instant> nextAttempt(DocumentId id) {
            return Optional.ofNullable(next.get(id));
        }
    }

    static final class RecordingEvents implements DocumentEvents {
        final List<String> events = new ArrayList<>();

        @Override
        public void documentArchivalFailed(DocumentId id, String title, MemberId uploadedBy, String failureCode) {
            events.add("failed:" + id + ":" + failureCode);
        }
    }

    /** The persons of the household; the assistant account is never one of them (ADR-0017). */
    static final class Persons implements dev.aegis.documents.application.ports.HouseholdPersons {
        final List<MemberId> persons = new ArrayList<>();

        Persons(MemberId... ids) {
            persons.addAll(List.of(ids));
        }

        @Override
        public List<MemberId> persons() {
            return List.copyOf(persons);
        }
    }

    static final class MembersOnly implements HouseholdAccess {
        final Set<MemberId> members = new LinkedHashSet<>();

        MembersOnly(MemberId... ids) {
            members.addAll(List.of(ids));
        }

        @Override
        public void requireMember(Actor actor) {
            if (actor == null || !members.contains(actor.memberId())) {
                throw new NotAuthorizedException("not a member");
            }
        }
    }

    /** AI boundary stand-in: records requests, can refuse them and reports controlled run states. */
    static final class RecordingRuns implements AnalysisRuns {
        final List<String> requested = new ArrayList<>();
        final Map<String, RunStatus> states = new HashMap<>();
        String refusal;

        @Override
        public Outcome request(Actor requester, DocumentId id, int generation, long classificationVersion) {
            if (refusal != null) {
                return new Unavailable(refusal);
            }
            String key = AnalysisRuns.key(id, generation);
            requested.add(key + "@" + classificationVersion + " by " + requester.memberId());
            UUID run = UUID.randomUUID();
            states.put(key, new RunStatus(run, "QUEUED", null, null, null, null));
            return new Requested(run);
        }

        void finish(DocumentId id, int generation, String state, String category, String code) {
            String key = AnalysisRuns.key(id, generation);
            states.put(key, new RunStatus(states.get(key).runId(), state, category, code, null,
                    Instant.parse("2026-09-13T10:05:00Z")));
        }

        @Override
        public Map<String, RunStatus> latest(Collection<String> keys) {
            Map<String, RunStatus> result = new HashMap<>();
            keys.forEach(key -> Optional.ofNullable(states.get(key)).ifPresent(status -> result.put(key, status)));
            return result;
        }
    }

    static AnalysisSettings analysisSettings() {
        return new AnalysisSettings(true, BuiltinLabelNames.Language.DE, 5_000, 200,
                ZoneId.of("Europe/Berlin"));
    }

    static DocumentPresenter presenter(ProcessingScheduler scheduler, ClassificationViews views) {
        return presenter(scheduler, views, new InMemoryArchiveTextCheckRepository());
    }

    static DocumentPresenter presenter(ProcessingScheduler scheduler, ClassificationViews views,
            InMemoryArchiveTextCheckRepository textChecks) {
        return new DocumentPresenter(scheduler, views, new AnalysisViews(new InMemoryDocumentAnalysisRepository(),
                new RecordingRuns(), analysisSettings()), textChecks);
    }

    static final class FakeArchive implements PaperlessArchive {
        boolean available = true;
        final Map<Integer, byte[]> originals = new HashMap<>();
        final Map<Integer, byte[]> archivePdfs = new HashMap<>();
        final Map<Integer, String> texts = new HashMap<>();
        final Map<String, List<Integer>> contentHits = new HashMap<>();
        int contentReads;

        @Override
        public String fetchContent(int paperlessDocumentId) {
            requireAvailable();
            contentReads++;
            return texts.getOrDefault(paperlessDocumentId, "");
        }

        @Override
        public ArchivedText fetchArchivedText(int paperlessDocumentId) {
            return new ArchivedText(fetchContent(paperlessDocumentId), archivePdfs.containsKey(paperlessDocumentId));
        }

        @Override
        public SubmissionOutcome submit(Path file, String title, String originalFilename, String contentType) {
            requireAvailable();
            return new Accepted("task-" + title);
        }

        @Override
        public TaskOutcome checkTask(String taskId) {
            requireAvailable();
            return new Pending();
        }

        @Override
        public OriginalContent fetchOriginal(int paperlessDocumentId) {
            requireAvailable();
            byte[] bytes = originals.get(paperlessDocumentId);
            if (bytes == null) {
                throw new ArchiveUnavailableException("missing " + paperlessDocumentId);
            }
            return new OriginalContent("x.pdf", "application/pdf", bytes, OriginalContent.SOURCE_ARCHIVE);
        }

        @Override
        public java.util.Optional<byte[]> fetchArchivePdf(int paperlessDocumentId) {
            requireAvailable();
            if (!originals.containsKey(paperlessDocumentId)) {
                throw new ArchiveUnavailableException("missing " + paperlessDocumentId);
            }
            return java.util.Optional.ofNullable(archivePdfs.get(paperlessDocumentId));
        }

        @Override
        public boolean exists(int paperlessDocumentId) {
            requireAvailable();
            return originals.containsKey(paperlessDocumentId);
        }

        @Override
        public List<Integer> searchContent(String query) {
            requireAvailable();
            return contentHits.getOrDefault(query, List.of());
        }

        @Override
        public ArchiveProbe probe() {
            return new ArchiveProbe(available, available ? "3.1.3" : null, available ? null : "down");
        }

        private void requireAvailable() {
            if (!available) {
                throw new ArchiveUnavailableException("archive down");
            }
        }
    }
}
