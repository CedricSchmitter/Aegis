package dev.aegis.documents.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.aegis.documents.application.PageEditDoubles.InMemoryDerivations;
import dev.aegis.documents.application.api.DerivationPortability.DerivationRecord;
import dev.aegis.documents.application.api.DerivationPortability.ImportOutcome;
import dev.aegis.documents.application.api.DerivationPortability.ImportResult;
import dev.aegis.documents.application.api.DerivationPortability.RangeRecord;
import dev.aegis.documents.domain.Checksum;
import dev.aegis.documents.domain.Document;
import dev.aegis.documents.domain.pages.Derivation;
import dev.aegis.documents.domain.pages.Derivation.Range;
import dev.aegis.documents.domain.pages.Derivation.SourceKind;
import dev.aegis.shared.Actor;
import dev.aegis.shared.DocumentId;
import dev.aegis.shared.InvalidInputException;
import dev.aegis.shared.MemberId;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class DerivationPortabilityServiceTest {
    private static final Instant NOW = Instant.parse("2026-10-02T09:00:00Z");
    private final MemberId lisa = MemberId.random();
    private final Actor actor = new Actor(lisa);
    private final UUID pageEdit = UUID.randomUUID();
    private InMemoryDocumentRepository documents;
    private InMemoryDerivations derivations;
    private DerivationPortabilityService service;

    @BeforeEach
    void setUp() {
        documents = new InMemoryDocumentRepository();
        derivations = new InMemoryDerivations();
        service = new DerivationPortabilityService(documents, derivations, ignored -> {
        });
    }

    @Test
    void exportsEveryRangeWithItsSourceScanAndDeletedSource() {
        DocumentId result = stored("Teil 1");
        DocumentId source = stored("Stromrechnung");
        derivations.insertIfAbsent(new Derivation(result, pageEdit, lisa, NOW, List.of(
                new Range(1, 2, SourceKind.DOCUMENT, source, 3, 4, 90),
                new Range(3, 3, SourceKind.SCAN, null, 1, 1, 0),
                new Range(4, 4, SourceKind.DOCUMENT, null, 7, 7, 180))));

        assertThat(service.exportAll(actor)).containsExactly(new DerivationRecord(result, pageEdit, lisa, NOW,
                List.of(new RangeRecord(1, 2, "DOCUMENT", source, 3, 4, 90),
                        new RangeRecord(3, 3, "SCAN", null, 1, 1, 0),
                        new RangeRecord(4, 4, "DOCUMENT", null, 7, 7, 180))));
    }

    @Test
    void restoresARangeWhoseSourceIsNotStoredAsADeletedSourceAndRepeatingChangesNothing() {
        DocumentId result = stored("Teil 1");
        DocumentId present = stored("Mietvertrag");
        DocumentId missing = DocumentId.random();
        DerivationRecord record = new DerivationRecord(result, pageEdit, lisa, NOW, List.of(
                new RangeRecord(1, 1, "DOCUMENT", present, 1, 1, 0),
                new RangeRecord(2, 3, "DOCUMENT", missing, 2, 3, 270),
                new RangeRecord(4, 4, "SCAN", null, 1, 1, 0)));

        assertThat(service.importDerivation(actor, record)).isEqualTo(new ImportResult(ImportOutcome.IMPORTED, 1));

        Derivation restored = derivations.findByDocument(result).orElseThrow();
        assertThat(restored.pageEdit()).isEqualTo(pageEdit);
        assertThat(restored.createdBy()).isEqualTo(lisa);
        assertThat(restored.createdAt()).isEqualTo(NOW);
        assertThat(restored.ranges()).containsExactly(new Range(1, 1, SourceKind.DOCUMENT, present, 1, 1, 0),
                new Range(2, 3, SourceKind.DOCUMENT, null, 2, 3, 270), new Range(4, 4, SourceKind.SCAN, null, 1, 1, 0));
        assertThat(restored.ranges().get(1).sourceDeleted()).isTrue();

        // Restoring again, even once the source exists, keeps the derivation as it is.
        documents.save(document(missing, "Spät"));
        assertThat(service.importDerivation(actor, record))
                .isEqualTo(new ImportResult(ImportOutcome.ALREADY_PRESENT, 0));
        assertThat(derivations.findByDocument(result).orElseThrow()).isEqualTo(restored);
    }

    @Test
    void skipsADerivationWhoseDocumentIsNotStored() {
        DocumentId absent = DocumentId.random();

        ImportResult result = service.importDerivation(actor, new DerivationRecord(absent, pageEdit, lisa, NOW,
                List.of(new RangeRecord(1, 1, "SCAN", null, 1, 1, 0))));

        assertThat(result.outcome()).isEqualTo(ImportOutcome.DOCUMENT_MISSING);
        assertThat(derivations.findAll()).isEmpty();
    }

    @Test
    void refusesInconsistentRangesWithAStableCode() {
        DocumentId result = stored("Teil 1");

        for (RangeRecord invalid : List.of(new RangeRecord(1, 2, "DOCUMENT", null, 1, 1, 0),
                new RangeRecord(1, 1, "PHOTO", null, 1, 1, 0), new RangeRecord(1, 1, "SCAN", null, 1, 1, 45))) {
            assertThatThrownBy(() -> service.importDerivation(actor, new DerivationRecord(result, pageEdit, lisa,
                    NOW, List.of(invalid)))).isInstanceOfSatisfying(InvalidInputException.class,
                            refused -> assertThat(refused.code()).isEqualTo("export.derivation.invalid"));
        }
        assertThat(derivations.findAll()).isEmpty();
    }

    private DocumentId stored(String title) {
        DocumentId id = DocumentId.random();
        documents.save(document(id, title));
        return id;
    }

    private Document document(DocumentId id, String title) {
        return Document.received(id, title, title + ".pdf", "application/pdf", 42,
                new Checksum(String.format("%064x", Math.abs(id.value().hashCode()))), lisa, NOW, Set.of(),
                id + ".pdf");
    }
}
