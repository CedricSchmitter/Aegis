package dev.aegis.documents.application;

import dev.aegis.documents.application.api.OriginalContent;
import dev.aegis.documents.application.ports.ArchiveUnavailableException;
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
        public void documentArchived(DocumentId id, String title, MemberId uploadedBy) {
            events.add("archived:" + id);
        }

        @Override
        public void documentArchivalFailed(DocumentId id, String title, MemberId uploadedBy, String failureCode) {
            events.add("failed:" + id + ":" + failureCode);
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

    static final class FakeArchive implements PaperlessArchive {
        boolean available = true;
        final Map<Integer, byte[]> originals = new HashMap<>();
        final Map<String, List<Integer>> contentHits = new HashMap<>();

        @Override
        public SubmissionOutcome submit(Path file, String title, String originalFilename) {
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
