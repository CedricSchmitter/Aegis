package dev.aegis.review.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.aegis.shared.MemberId;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ReviewItemTest {
    private static final Instant CREATED = Instant.parse("2026-09-23T08:00:00Z");
    private static final Instant LATER = Instant.parse("2026-09-23T09:00:00Z");
    private static final MemberId LISA = MemberId.random();

    @Test
    void aMemberDecisionNamesTheMember() {
        ReviewItem card = open();
        card.decide(ReviewStatus.DISMISSED, LISA, LATER);
        assertThat(card.status()).isEqualTo(ReviewStatus.DISMISSED);
        assertThat(card.decidedBy()).isEqualTo(LISA);
        assertThat(card.decidedAt()).isEqualTo(LATER);
        assertThat(card.version()).isEqualTo(1);
    }

    @Test
    void onlyAMemberResolvesOrDismissesACard() {
        assertThatThrownBy(() -> open().decide(ReviewStatus.DISMISSED, null, LATER))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> open().decide(ReviewStatus.SUPERSEDED, LISA, LATER))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> open().decide(ReviewStatus.OPEN, LISA, LATER))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void supersedingRecordsNoMember() {
        ReviewItem card = open();
        card.supersede(LATER);
        assertThat(card.status()).isEqualTo(ReviewStatus.SUPERSEDED);
        assertThat(card.decidedBy()).isNull();
        assertThat(card.decidedAt()).isEqualTo(LATER);
        assertThat(card.updatedAt()).isEqualTo(LATER);
        assertThat(card.version()).isEqualTo(1);
    }

    @Test
    void aClosedCardIsNeitherSupersededNorDecidedAgain() {
        ReviewItem card = open();
        card.decide(ReviewStatus.RESOLVED, LISA, LATER);
        card.supersede(LATER.plusSeconds(60));
        card.decide(ReviewStatus.DISMISSED, MemberId.random(), LATER.plusSeconds(120));
        assertThat(card.status()).isEqualTo(ReviewStatus.RESOLVED);
        assertThat(card.decidedBy()).isEqualTo(LISA);
        assertThat(card.version()).isEqualTo(1);
    }

    private static ReviewItem open() {
        return ReviewItem.create(UUID.randomUUID(), new ReviewItem.Candidate("no-text:1:7", "documents", "DOCUMENT",
                "1", 7, "PROCESSING_FAILURE", "NO_TEXT_RECOGNIZED", "NORMAL", "review.noText",
                Map.of("title", "Brief"), "DOCUMENT", "1", List.of()), CREATED);
    }
}
