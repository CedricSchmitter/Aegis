package dev.aegis.documents.application;

import static org.assertj.core.api.Assertions.assertThat;

import dev.aegis.documents.application.ArchivalUseCases.Finished;
import dev.aegis.documents.application.ArchivalUseCases.Poll;
import dev.aegis.documents.application.ArchivalUseCases.Submit;
import dev.aegis.documents.domain.Checksum;
import dev.aegis.documents.domain.Document;
import dev.aegis.documents.domain.ProcessingState;
import dev.aegis.documents.domain.Subject;
import dev.aegis.shared.DocumentId;
import dev.aegis.shared.MemberId;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ArchivalServiceTest {
    private static final Instant NOW = Instant.parse("2026-09-13T10:00:00Z");
    private InMemoryDocumentRepository repository;
    private TestDoubles.RecordingEvents events;
    private final java.util.List<DocumentId> triggered = new java.util.ArrayList<>();
    private ArchivalService service;
    private Document document;

    @BeforeEach
    void setUp() {
        repository = new InMemoryDocumentRepository();
        events = new TestDoubles.RecordingEvents();
        service = new ArchivalService(repository, events, archived -> triggered.add(archived.id()),
                new ArchivalSettings(2, Duration.ofMinutes(5), Duration.ofDays(7), 1024),
                Clock.fixed(NOW, ZoneOffset.UTC));
        document = Document.received(DocumentId.random(), "Rechnung", "rechnung.pdf", "application/pdf", 10,
                new Checksum("b".repeat(64)), MemberId.random(), NOW, Set.of(Subject.household()), "x.pdf");
        repository.save(document);
    }

    @Test
    void walksFromSubmitToPollToFinishedAndNotifiesOnce() {
        assertThat(service.prepare(document.id())).isInstanceOf(Submit.class);

        service.recordSubmission(document.id(), "task-1");
        assertThat(service.prepare(document.id())).isEqualTo(new Poll(document.id(), "task-1", NOW));

        service.recordArchived(document.id(), 42);
        service.recordArchived(document.id(), 42);

        assertThat(service.prepare(document.id())).isInstanceOf(Finished.class);
        assertThat(repository.findById(document.id()).orElseThrow().paperlessDocumentId()).contains(42);
        assertThat(events.events).containsExactly("archived:" + document.id());
    }

    @Test
    void exhaustedRetriesFailTheDocumentAndNotifyTheUploader() {
        var first = service.recordFailure(document.id(), "paperless.unavailable", "refused", true);
        var second = service.recordFailure(document.id(), "paperless.unavailable", "refused", true);

        assertThat(first.retry()).isTrue();
        assertThat(second.retry()).isFalse();
        assertThat(repository.findById(document.id()).orElseThrow().state()).isEqualTo(ProcessingState.FAILED);
        assertThat(service.prepare(document.id())).isInstanceOf(Finished.class);
        assertThat(events.events).containsExactly("failed:" + document.id() + ":paperless.unavailable");
    }

    @Test
    void missingRecordFinishesWithoutFailing() {
        assertThat(service.prepare(DocumentId.random())).isInstanceOf(Finished.class);
    }

    @Test
    void lateFailureAfterArchivalIsIgnored() {
        service.recordArchived(document.id(), 7);

        var decision = service.recordFailure(document.id(), "paperless.timeout", "late", true);

        assertThat(decision.retry()).isFalse();
        assertThat(repository.findById(document.id()).orElseThrow().state()).isEqualTo(ProcessingState.ARCHIVED);
    }
}
