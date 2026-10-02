package dev.aegis.documents.domain.pages;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.aegis.documents.domain.pages.Derivation.Range;
import dev.aegis.documents.domain.pages.Derivation.SourceKind;
import dev.aegis.shared.DocumentId;
import dev.aegis.shared.MemberId;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class DerivationTest {
    private static final Instant NOW = Instant.parse("2026-10-02T09:00:00Z");
    private final DocumentId result = DocumentId.random();
    private final DocumentId letter = DocumentId.random();
    private final DocumentId annex = DocumentId.random();
    private final MemberId lisa = MemberId.random();

    @Test
    void consecutivePagesOfOneSourceWithTheSameRotationFormOneRange() {
        Derivation derivation = Derivation.of(result, UUID.randomUUID(), lisa, NOW, List.of(
                PageRef.ofDocument(letter, 2, 0), PageRef.ofDocument(letter, 3, 0), PageRef.ofDocument(letter, 4, 90),
                PageRef.ofDocument(annex, 0, 0), PageRef.ofScan(0, 0), PageRef.ofScan(1, 0),
                PageRef.ofDocument(letter, 0, 0)));

        assertThat(derivation.ranges()).containsExactly(
                new Range(1, 2, SourceKind.DOCUMENT, letter, 3, 4, 0),
                new Range(3, 3, SourceKind.DOCUMENT, letter, 5, 5, 90),
                new Range(4, 4, SourceKind.DOCUMENT, annex, 1, 1, 0),
                new Range(5, 6, SourceKind.SCAN, null, 1, 2, 0),
                new Range(7, 7, SourceKind.DOCUMENT, letter, 1, 1, 0));
        assertThat(derivation.sources()).containsExactly(letter, annex);
    }

    @Test
    void reorderedOrRepeatedPagesStartANewRange() {
        Derivation derivation = Derivation.of(result, UUID.randomUUID(), lisa, NOW, List.of(
                PageRef.ofDocument(letter, 1, 0), PageRef.ofDocument(letter, 0, 0), PageRef.ofDocument(letter, 0, 0)));
        assertThat(derivation.ranges()).extracting(Range::firstSourcePage).containsExactly(2, 1, 1);
    }

    @Test
    void aDocumentRangeWithoutSourceShowsADeletedSource() {
        Range deleted = new Range(1, 2, SourceKind.DOCUMENT, null, 4, 5, 0);
        assertThat(deleted.sourceDeleted()).isTrue();
        assertThat(new Range(1, 1, SourceKind.SCAN, null, 1, 1, 0).sourceDeleted()).isFalse();
        assertThatThrownBy(() -> new Range(1, 2, SourceKind.DOCUMENT, letter, 1, 3, 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Range(1, 1, SourceKind.SCAN, letter, 1, 1, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
