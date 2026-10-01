package dev.aegis.notifications.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.aegis.notifications.application.api.NoticeDraft;
import dev.aegis.notifications.application.api.PublishNotice.PublishOutcome;
import dev.aegis.notifications.domain.Notice;
import dev.aegis.notifications.domain.NoticeId;
import dev.aegis.notifications.domain.NoticeKind;
import dev.aegis.notifications.domain.NoticeLink;
import dev.aegis.notifications.domain.NoticeRepository;
import dev.aegis.shared.Actor;
import dev.aegis.shared.HouseholdAccess;
import dev.aegis.shared.InvalidInputException;
import dev.aegis.shared.MemberId;
import dev.aegis.shared.NotAuthorizedException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class NoticeServiceTest {
    private final MemberId anna = MemberId.random();
    private final MemberId ben = MemberId.random();
    private final InMemoryNotices repository = new InMemoryNotices();
    private NoticeService service;

    static final class InMemoryNotices implements NoticeRepository {
        private final Map<NoticeId, Notice> store = new LinkedHashMap<>();

        @Override
        public Optional<Notice> findById(NoticeId id) {
            return Optional.ofNullable(store.get(id));
        }

        @Override
        public List<Notice> findByRecipient(MemberId recipient, Set<NoticeKind> kinds, boolean unreadOnly) {
            return store.values().stream().filter(n -> n.recipient().equals(recipient))
                    .filter(n -> kinds.contains(n.kind()))
                    .filter(n -> !unreadOnly || n.isUnread())
                    .sorted(Comparator.comparing(Notice::createdAt).reversed()).toList();
        }

        @Override
        public Optional<Notice> findByDedupKey(MemberId recipient, String dedupKey) {
            return store.values().stream().filter(n -> n.recipient().equals(recipient)
                    && n.dedupKey().equals(dedupKey)).findFirst();
        }

        @Override
        public long countUnread(MemberId recipient, Set<NoticeKind> kinds) {
            return findByRecipient(recipient, kinds, true).size();
        }

        @Override
        public void save(Notice notice) {
            store.put(notice.id(), notice);
        }

        @Override
        public boolean saveIfAbsent(Notice notice) {
            if (findByDedupKey(notice.recipient(), notice.dedupKey()).isPresent()) {
                return false;
            }
            save(notice);
            return true;
        }

        @Override
        public int deleteLinkedTo(NoticeLink link) {
            List<NoticeId> linked = store.values().stream()
                    .filter(n -> n.link().map(link::equals).orElse(false)).map(Notice::id).toList();
            linked.forEach(store::remove);
            return linked.size();
        }
    }

    @BeforeEach
    void setUp() {
        HouseholdAccess access = actor -> {
            if (!List.of(anna, ben).contains(actor.memberId())) {
                throw new NotAuthorizedException("no");
            }
        };
        service = new NoticeService(repository, access,
                Clock.fixed(Instant.parse("2026-09-13T10:00:00Z"), ZoneOffset.UTC));
    }

    private NoticeDraft assigned(MemberId recipient, String taskId) {
        return new NoticeDraft(recipient, "TASK_ASSIGNED", "task-assigned:" + taskId, Map.of("title", "Pay"),
                "TASK", taskId);
    }

    @Test
    void repeatedNoticeAboutTheSameFactStaysDeduplicatedAfterItIsRead() {
        assertThat(service.publish(assigned(anna, "t1"))).isEqualTo(PublishOutcome.PUBLISHED);
        assertThat(service.publish(assigned(anna, "t1"))).isEqualTo(PublishOutcome.DEDUPLICATED);
        assertThat(service.unreadCount(new Actor(anna))).isEqualTo(1);

        service.markAllRead(new Actor(anna));
        assertThat(service.publish(assigned(anna, "t1"))).isEqualTo(PublishOutcome.DEDUPLICATED);
    }

    @Test
    void readStateIsPerMemberAndOnlyTheRecipientCanMarkRead() {
        service.publish(assigned(anna, "t1"));
        service.publish(assigned(ben, "t1"));
        String annasNotice = service.list(new Actor(anna), true).get(0).id();

        service.markRead(new Actor(anna), annasNotice);

        assertThat(service.unreadCount(new Actor(anna))).isZero();
        assertThat(service.unreadCount(new Actor(ben))).isEqualTo(1);
        assertThat(service.list(new Actor(anna), false)).hasSize(1);
        assertThat(service.list(new Actor(ben), false)).extracting("linkId").containsExactly("t1");
        String bensNotice = service.list(new Actor(ben), true).get(0).id();
        assertThatThrownBy(() -> service.markRead(new Actor(anna), bensNotice))
                .isInstanceOf(NotAuthorizedException.class);
    }

    private NoticeDraft reviewCard(MemberId recipient, String cardId) {
        return new NoticeDraft(recipient, "REVIEW_ITEM", "review:" + cardId,
                Map.of("titleKey", "review.noText", "title", "Scan"), "REVIEW", cardId);
    }

    private NoticeDraft archivalFailed(MemberId recipient, String documentId) {
        return new NoticeDraft(recipient, "DOCUMENT_ARCHIVAL_FAILED", "document-failed:" + documentId,
                Map.of("title", "Scan", "code", "paperless.rejected"), "DOCUMENT", documentId);
    }

    @Test
    void theInboxListsAndCountsOnlyMessagesBecauseReviewCardsShowTheirOwnNotices() {
        service.publish(assigned(anna, "t1"));
        service.publish(reviewCard(anna, "c1"));
        service.publish(archivalFailed(anna, "d1"));
        service.publish(new NoticeDraft(anna, "JOB_STALE", "job-health:i1", Map.of("job", "watch-job-health"),
                "STATUS", "watch-job-health"));
        // A notice stored before issue #83 retired its kind stays in the database but is no message.
        repository.save(Notice.create(anna, NoticeKind.DOCUMENT_ARCHIVED, "document-archived:d0", Map.of(), null,
                Instant.parse("2026-09-01T10:00:00Z")));

        assertThat(service.list(new Actor(anna), false)).extracting("kind")
                .containsExactlyInAnyOrder("TASK_ASSIGNED", "JOB_STALE");
        assertThat(service.list(new Actor(anna), true)).hasSize(2);
        assertThat(service.unreadCount(new Actor(anna))).isEqualTo(2);
    }

    @Test
    void openingTheInboxMarksOnlyTheReviewCardNoticesOfThatMemberRead() {
        service.publish(assigned(anna, "t1"));
        service.publish(reviewCard(anna, "c1"));
        service.publish(archivalFailed(anna, "d1"));
        service.publish(reviewCard(ben, "c1"));

        assertThat(service.markReviewCardNoticesRead(new Actor(anna))).isEqualTo(2);
        assertThat(service.markReviewCardNoticesRead(new Actor(anna))).isZero();

        assertThat(repository.findByRecipient(anna, Set.of(NoticeKind.REVIEW_ITEM,
                NoticeKind.DOCUMENT_ARCHIVAL_FAILED), true)).isEmpty();
        assertThat(repository.findByRecipient(ben, Set.of(NoticeKind.REVIEW_ITEM), true)).hasSize(1);
        // Messages stay unread until the member reads them.
        assertThat(service.unreadCount(new Actor(anna))).isEqualTo(1);
        assertThatThrownBy(() -> service.markReviewCardNoticesRead(new Actor(MemberId.random())))
                .isInstanceOf(NotAuthorizedException.class);
    }

    @Test
    void aRetiredKindIsNotPublishedAgain() {
        NoticeDraft archived = new NoticeDraft(anna, "DOCUMENT_ARCHIVED", "document-archived:d1",
                Map.of("title", "Scan"), "DOCUMENT", "d1");

        assertThatThrownBy(() -> service.publish(archived)).isInstanceOf(InvalidInputException.class)
                .hasFieldOrPropertyWithValue("code", "notice.kind.retired");
        assertThat(repository.findByRecipient(anna, Set.of(NoticeKind.values()), false)).isEmpty();
    }

    @Test
    void everyKindHasExactlyOnePlaceInTheInbox() {
        assertThat(NoticeKind.placedAs(NoticeKind.Placement.MESSAGE)).containsExactlyInAnyOrder(
                NoticeKind.TASK_ASSIGNED, NoticeKind.TASK_COMPLETED, NoticeKind.CONTRACT_CANCELLATION_REMINDER,
                NoticeKind.JOB_STALE, NoticeKind.BACKUP_FAILED, NoticeKind.DISK_SPACE_LOW);
        assertThat(NoticeKind.placedAs(NoticeKind.Placement.REVIEW_CARD))
                .containsExactlyInAnyOrder(NoticeKind.REVIEW_ITEM, NoticeKind.DOCUMENT_ARCHIVAL_FAILED);
        assertThat(NoticeKind.placedAs(NoticeKind.Placement.RETIRED)).containsExactly(NoticeKind.DOCUMENT_ARCHIVED);
    }
}