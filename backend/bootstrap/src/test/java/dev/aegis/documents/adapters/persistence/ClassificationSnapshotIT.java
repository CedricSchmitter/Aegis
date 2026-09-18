package dev.aegis.documents.adapters.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.aegis.bootstrap.AegisApplication;
import dev.aegis.bootstrap.support.PostgresIT;
import dev.aegis.documents.application.api.ClassificationCommands;
import dev.aegis.documents.application.api.LabelCommands;
import dev.aegis.documents.domain.Checksum;
import dev.aegis.documents.domain.Document;
import dev.aegis.documents.domain.DocumentRepository;
import dev.aegis.documents.domain.catalog.LabelId;
import dev.aegis.documents.domain.classification.ClassificationRepository;
import dev.aegis.documents.domain.classification.DocumentClassification;
import dev.aegis.identity.application.api.MemberCredentialsQuery;
import dev.aegis.shared.Actor;
import dev.aegis.shared.DocumentId;
import dev.aegis.shared.StaleVersionException;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.PreparedStatementCreator;
import org.springframework.jdbc.core.PreparedStatementSetter;
import org.springframework.jdbc.core.ResultSetExtractor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** A committed edit between SQL statements must never attach a fresh version to stale assignments. */
@SpringBootTest(classes = AegisApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ClassificationSnapshotIT extends PostgresIT {
    @Autowired DataSource dataSource;
    @Autowired PlatformTransactionManager transactions;
    @Autowired DocumentRepository documents;
    @Autowired ClassificationRepository classifications;
    @Autowired ClassificationCommands commands;
    @Autowired LabelCommands labels;
    @Autowired MemberCredentialsQuery members;

    private DocumentId documentId;

    @AfterEach
    void removeSyntheticDocument() {
        if (documentId != null) {
            JdbcTemplate jdbc = new JdbcTemplate(dataSource);
            jdbc.update("DELETE FROM documents WHERE id = ?", documentId.value());
            jdbc.update("DELETE FROM labels WHERE name IN (?, ?, ?)", "Snapshot first " + documentId,
                    "Snapshot second " + documentId, "Snapshot replacement " + documentId);
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void assignmentsAndVersionStayTogetherWhenAnotherMemberCommitsDuringRead(boolean initiallyLabeled) {
        Actor reader = new Actor(members.findCredentials(ANNA).orElseThrow().id());
        Actor writer = new Actor(members.findCredentials(BEN).orElseThrow().id());
        DocumentId id = DocumentId.random();
        documentId = id;
        Document document = Document.received(id, "Snapshot regression", "snapshot.pdf", "application/pdf", 42,
                new Checksum(id.toString().replace("-", "").repeat(2)), reader.memberId(), Instant.now(),
                Set.of(), "snapshot/" + id + ".pdf");
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            documents.save(document);
            classifications.save(DocumentClassification.unsorted(id));
        });
        UUID first = labels.create(reader, null, "TAG", "Snapshot first " + id).id();
        UUID second = labels.create(reader, null, "TAG", "Snapshot second " + id).id();
        UUID replacement = labels.create(writer, null, "TAG", "Snapshot replacement " + id).id();
        List<UUID> initial = initiallyLabeled ? List.of(first, second) : List.of();
        long version = commands.setLabels(reader, id, initial,
                classifications.findById(id).orElseThrow().version()).classification().version();

        AtomicBoolean committed = new AtomicBoolean();
        JdbcTemplate interleaved = new JdbcTemplate(dataSource) {
            @Override
            public <T> T query(PreparedStatementCreator creator, PreparedStatementSetter setter,
                    ResultSetExtractor<T> extractor) {
                T result = super.query(creator, setter, extractor);
                // The real PostgreSQL query has finished. Commit the other member's edit before any next
                // statement can read the root version; no sleeps or probabilistic thread scheduling.
                if (committed.compareAndSet(false, true)) {
                    commands.setLabels(writer, id, List.of(replacement), version);
                }
                return result;
            }
        };
        JdbcClassificationRepository reading = new JdbcClassificationRepository(JdbcClient.create(interleaved));
        var loaded = reading.findAll(List.of(id, DocumentId.random()));

        assertThat(committed).isTrue();
        assertThat(loaded).containsOnlyKeys(id);
        DocumentClassification snapshot = loaded.get(id);
        assertThat(snapshot.version()).isEqualTo(version);
        assertThat(snapshot.effectiveLabelIds()).containsExactlyInAnyOrderElementsOf(
                initial.stream().map(LabelId::new).toList());
        assertThat(classifications.findById(id).orElseThrow().effectiveLabelIds())
                .containsExactly(new LabelId(replacement));
        assertThatThrownBy(() -> commands.setLabels(reader, id, initial, snapshot.version()))
                .isInstanceOf(StaleVersionException.class);
    }
}
