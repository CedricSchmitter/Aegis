package dev.aegis.notifications.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.aegis.notifications.application.api.NoticeDraft;
import dev.aegis.notifications.application.api.PublishNotice.PublishOutcome;
import dev.aegis.notifications.domain.Notice;
import dev.aegis.notifications.domain.NoticeId;
import dev.aegis.notifications.domain.NoticeRepository;
import dev.aegis.shared.Actor;
import dev.aegis.shared.HouseholdAccess;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class NoticeServiceTest {
    private final MemberId anna = MemberId.random();
    private final MemberId ben = MemberId.random();
    private NoticeService service;

    static final class InMemoryNotices implements NoticeRepository {
        private final Map<NoticeId, Notice> store = new LinkedHashMap<>();

        @Override
        public Optional<Notice> findById(NoticeId id) {
            return Optional.ofNullable(store.get(id));
        }

        @Override
        public List<Notice> findByRecipient(MemberId recipient, boolean unreadOnly) {
            return store.values().stream().filter(n -> n.recipient().equals(recipient))
                    .filter(n -> !unreadOnly || n.isUnread())
                    .sorted(Comparator.comparing(Notice::createdAt).reversed()).toList();
        }

        @Override
        public Optional<Notice> findByDedupKey(MemberId recipient, String dedupKey) {
            return store.values().stream().filter(n -> n.recipient().equals(recipient)
                    && n.dedupKey().equals(dedupKey)).findFirst();
        }

        @Override
        public long countUnread(MemberId recipient) {
            return findByRecipient(recipient, true).size();
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
    }

    @BeforeEach
    void setUp() {
        HouseholdAccess access = actor -> {
            if (!List.of(anna, ben).contains(actor.memberId())) {
                throw new NotAuthorizedException("no");
            }
        };
        service = new NoticeService(new InMemoryNotices(), access,
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
}
