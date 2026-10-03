package dev.aegis.notifications.application.push;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.aegis.notifications.application.api.PushDispatch;
import dev.aegis.notifications.application.api.PushSettings;
import dev.aegis.notifications.domain.Notice;
import dev.aegis.notifications.domain.NoticeId;
import dev.aegis.notifications.domain.NoticeKind;
import dev.aegis.notifications.domain.NoticeLink;
import dev.aegis.notifications.domain.NoticeRepository;
import dev.aegis.notifications.domain.push.PushDeliveryLog;
import dev.aegis.notifications.domain.push.PushDeliveryState;
import dev.aegis.notifications.domain.push.PushPreferences;
import dev.aegis.notifications.domain.push.PushPreferencesRepository;
import dev.aegis.notifications.domain.push.PushSubscription;
import dev.aegis.notifications.domain.push.PushSubscriptionId;
import dev.aegis.notifications.domain.push.PushSubscriptionRepository;
import dev.aegis.shared.Actor;
import dev.aegis.shared.InvalidInputException;
import dev.aegis.shared.MemberId;
import dev.aegis.shared.NotAuthorizedException;
import dev.aegis.shared.NotFoundException;
import dev.aegis.shared.ProductLocale;
import dev.aegis.shared.StaleVersionException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class PushServiceTest {
    private static final ZoneId BERLIN = ZoneId.of("Europe/Berlin");
    private static final String P256DH;
    private static final String AUTH = Base64.getUrlEncoder().withoutPadding().encodeToString(new byte[16]);

    static {
        byte[] point = new byte[65];
        point[0] = 0x04;
        P256DH = Base64.getUrlEncoder().withoutPadding().encodeToString(point);
    }

    private final MemberId anna = MemberId.random();
    private final MemberId ben = MemberId.random();
    private final Subscriptions subscriptions = new Subscriptions();
    private final Preferences preferences = new Preferences();
    private final Deliveries deliveries = new Deliveries();
    private final Notices notices = new Notices();
    private final MutableClock clock = new MutableClock(Instant.parse("2026-10-03T10:00:00Z")); // 12:00 in Berlin
    private boolean keyAvailable = true;
    private PushService service;

    @BeforeEach
    void setUp() {
        PushTexts texts = new PushTexts() {
            @Override
            public Text notice(ProductLocale locale, NoticeKind kind, Map<String, String> params, boolean details) {
                return new Text("Aegis", locale + ":" + kind + (details ? ":" + params.get("title") : ""));
            }

            @Override
            public Text summary(ProductLocale locale, int count) {
                return new Text("Aegis", locale + ":summary:" + count);
            }

            @Override
            public Text test(ProductLocale locale) {
                return new Text("Aegis", locale + ":test");
            }
        };
        service = new PushService(subscriptions, preferences, deliveries, notices,
                () -> keyAvailable ? Optional.of("server-key") : Optional.empty(), texts,
                member -> member.equals(ben) ? ProductLocale.EN : ProductLocale.DE, PushServiceHosts.DEFAULT,
                actor -> {
                    if (actor.isHouseholdProcess()) {
                        throw new NotAuthorizedException("not a member");
                    }
                }, Duration.ofHours(24), BERLIN, clock);
    }

    private PushSettings.PushDevice subscribe(MemberId member, String path) {
        return service.subscribe(new Actor(member), new PushSettings.Subscribe(
                "https://fcm.googleapis.com/fcm/send/" + path, P256DH, AUTH, "Pixel"));
    }

    private Notice notice(MemberId recipient, NoticeKind kind, Instant at) {
        Notice notice = Notice.create(recipient, kind, kind + ":" + at, Map.of("title", "Steuer"),
                new NoticeLink(NoticeLink.LinkType.TASK, "task-1"), at);
        notices.save(notice);
        return notice;
    }

    private void record(List<PendingPush> pushes, PushSender.Outcome outcome) {
        pushes.forEach(push -> service.recordOutcome(push, new PushSender.Result(outcome, 201)));
    }

    @Test
    void aNewNoticeIsPushedOnceInTheRecipientsLocaleAndOpensItsTarget() {
        subscribe(ben, "ben-phone");
        clock.advance(Duration.ofMinutes(1));
        Notice assigned = notice(ben, NoticeKind.TASK_ASSIGNED, clock.instant());

        List<PendingPush> pending = service.pending();

        assertThat(pending).hasSize(1);
        assertThat(pending.getFirst().notices()).containsExactly(assigned.id());
        assertThat(pending.getFirst().message().body()).isEqualTo("EN:TASK_ASSIGNED");
        assertThat(pending.getFirst().message().path()).isEqualTo("/tasks/task-1");
        assertThat(pending.getFirst().message().tag()).isEqualTo("notice-" + assigned.id());
        assertThat(pending.getFirst().message().topic()).hasSize(32);

        record(pending, PushSender.Outcome.ACCEPTED);

        assertThat(service.pending()).isEmpty();
        assertThat(service.devices(new Actor(ben)).getFirst().lastSentAt()).isEqualTo(clock.instant());
    }

    @Test
    void onlyUnreadNoticesSinceSubscribingAndWithinTheCatchUpWindowArePushed() {
        notice(anna, NoticeKind.TASK_ASSIGNED, clock.instant().minusSeconds(60));
        clock.advance(Duration.ofMinutes(1));
        subscribe(anna, "anna-phone");
        Notice read = notice(anna, NoticeKind.TASK_COMPLETED, clock.instant());
        read.markRead(clock.instant());
        notices.save(read);
        notice(ben, NoticeKind.TASK_ASSIGNED, clock.instant());

        assertThat(service.pending()).isEmpty();

        Notice fresh = notice(anna, NoticeKind.TASK_ASSIGNED, clock.instant());
        clock.advance(Duration.ofHours(25));
        assertThat(service.pending()).as("older than the catch-up window").isEmpty();
        assertThat(fresh.isUnread()).isTrue();
    }

    @Test
    void severalDueNoticesBecomeOneSummaryThatOpensTheInbox() {
        subscribe(anna, "anna-phone");
        Notice first = notice(anna, NoticeKind.TASK_ASSIGNED, clock.instant());
        Notice second = notice(anna, NoticeKind.BACKUP_FAILED, clock.instant().plusSeconds(1));

        List<PendingPush> pending = service.pending();

        assertThat(pending).singleElement().satisfies(push -> {
            assertThat(push.notices()).containsExactly(first.id(), second.id());
            assertThat(push.message().body()).isEqualTo("DE:summary:2");
            assertThat(push.message().path()).isEqualTo("/inbox");
        });
    }

    @Test
    void quietHoursHoldPushesUntilTheyEndAndDisabledCategoriesAreNeverPushed() {
        subscribe(anna, "anna-phone");
        Actor actor = new Actor(anna);
        service.changePreferences(actor, new PushSettings.Preferences(Set.of("TASKS"), LocalTime.of(11, 0),
                LocalTime.of(13, 0), true, 0));
        notice(anna, NoticeKind.DISK_SPACE_LOW, clock.instant());
        Notice task = notice(anna, NoticeKind.TASK_ASSIGNED, clock.instant());

        assertThat(service.pending()).as("12:00 in Berlin is within 11:00-13:00").isEmpty();

        clock.advance(Duration.ofHours(1));
        assertThat(service.pending()).singleElement().satisfies(push -> {
            assertThat(push.notices()).containsExactly(task.id());
            assertThat(push.message().body()).isEqualTo("DE:TASK_ASSIGNED:Steuer");
        });
    }

    @Test
    void aFailedPushIsRetriedAfterAGrowingPauseAndThenGivenUp() {
        subscribe(anna, "anna-phone");
        notice(anna, NoticeKind.TASK_ASSIGNED, clock.instant());

        for (int attempt = 1; attempt <= PushService.MAX_ATTEMPTS; attempt++) {
            List<PendingPush> pending = service.pending();
            assertThat(pending).as("attempt " + attempt).hasSize(1);
            record(pending, PushSender.Outcome.UNAVAILABLE);
            assertThat(service.pending()).as("paused after attempt " + attempt).isEmpty();
            clock.advance(PushService.RETRY_STEP.multipliedBy(attempt));
        }
        assertThat(service.pending()).as("given up").isEmpty();
        assertThat(service.devices(new Actor(anna)).getFirst().state()).isEqualTo("FAILING");
    }

    @Test
    void aSubscriptionThePushServiceNoLongerKnowsExpiresAndGetsNothingMore() {
        PushSettings.PushDevice device = subscribe(anna, "anna-phone");
        notice(anna, NoticeKind.TASK_ASSIGNED, clock.instant());
        record(service.pending(), PushSender.Outcome.GONE);

        notice(anna, NoticeKind.TASK_COMPLETED, clock.instant().plusSeconds(1));
        assertThat(service.pending()).isEmpty();
        assertThat(service.devices(new Actor(anna))).singleElement()
                .satisfies(d -> assertThat(d.state()).isEqualTo("EXPIRED"));

        PushSettings.PushDevice renewed = subscribe(anna, "anna-phone");
        assertThat(renewed.id()).isEqualTo(device.id());
        assertThat(renewed.state()).isEqualTo("ACTIVE");
    }

    @Test
    void nothingIsPlannedWithoutAServerKey() {
        keyAvailable = false;
        subscribe(anna, "anna-phone");
        notice(anna, NoticeKind.TASK_ASSIGNED, clock.instant());

        assertThat(service.pending()).isEmpty();
        assertThat(service.config(new Actor(anna)).available()).isFalse();
    }

    @Test
    void membersManageOnlyTheirOwnDevicesAndOnlyKnownPushServices() {
        PushSettings.PushDevice annasPhone = subscribe(anna, "anna-phone");
        Actor benActor = new Actor(ben);

        assertThat(service.devices(benActor)).isEmpty();
        assertThatThrownBy(() -> service.removeDevice(benActor, annasPhone.id()))
                .isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> service.testPush(benActor, annasPhone.id())).isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> service.devices(Actor.householdProcess("job")))
                .isInstanceOf(NotAuthorizedException.class);
        assertThatThrownBy(() -> service.subscribe(benActor, new PushSettings.Subscribe(
                "https://192.168.2.1/internal", P256DH, AUTH, null)))
                .isInstanceOf(InvalidInputException.class)
                .satisfies(e -> assertThat(((InvalidInputException) e).code()).isEqualTo("push.endpoint.notAllowed"));

        assertThat(service.testPush(new Actor(anna), annasPhone.id()).message().body()).isEqualTo("DE:test");
        service.removeDevice(new Actor(anna), annasPhone.id());
        assertThat(service.devices(new Actor(anna))).isEmpty();
    }

    @Test
    void theSameBrowserSubscribingForTheOtherMemberMovesToThatMember() {
        PushSettings.PushDevice device = subscribe(anna, "shared-tablet");
        PushSettings.PushDevice moved = subscribe(ben, "shared-tablet");

        assertThat(moved.id()).isEqualTo(device.id());
        assertThat(service.devices(new Actor(anna))).isEmpty();
        assertThat(service.devices(new Actor(ben))).hasSize(1);
    }

    @Test
    void preferencesAreVersionedAndValidated() {
        Actor actor = new Actor(anna);
        PushSettings.Preferences defaults = service.preferences(actor);
        assertThat(defaults.categories()).containsExactlyInAnyOrder("TASKS", "DEADLINES", "REVIEW", "SYSTEM");
        assertThat(defaults.quietStart()).isEqualTo(LocalTime.of(22, 0));
        assertThat(defaults.showDetails()).isFalse();

        PushSettings.Preferences changed = service.changePreferences(actor,
                new PushSettings.Preferences(Set.of("REVIEW"), null, null, true, 0));
        assertThat(changed.version()).isEqualTo(1);
        assertThat(changed.quietStart()).isNull();

        assertThatThrownBy(() -> service.changePreferences(actor,
                new PushSettings.Preferences(Set.of(), null, null, false, 0)))
                .isInstanceOf(StaleVersionException.class);
        assertThatThrownBy(() -> service.changePreferences(actor,
                new PushSettings.Preferences(Set.of("EVERYTHING"), null, null, false, 1)))
                .isInstanceOf(InvalidInputException.class);
        assertThatThrownBy(() -> service.changePreferences(actor,
                new PushSettings.Preferences(Set.of(), LocalTime.NOON, null, false, 1)))
                .isInstanceOf(InvalidInputException.class);
        assertThat(service.preferences(actor).categories()).containsExactly("REVIEW");
    }

    @Test
    void theDispatcherSendsEachPushAndKeepsGoingWhenOneFails() {
        subscribe(anna, "anna-phone");
        subscribe(ben, "ben-phone");
        notice(anna, NoticeKind.TASK_ASSIGNED, clock.instant());
        notice(ben, NoticeKind.TASK_ASSIGNED, clock.instant());
        List<String> sent = new ArrayList<>();
        PushDispatcher dispatcher = new PushDispatcher(service, (endpoint, message) -> {
            if (endpoint.url().endsWith("anna-phone")) {
                throw new IllegalStateException("broken");
            }
            sent.add(message.body());
            return new PushSender.Result(PushSender.Outcome.ACCEPTED, 201);
        });

        assertThatThrownBy(dispatcher::dispatchPending).hasMessage("broken");
        assertThat(sent).containsExactly("EN:TASK_ASSIGNED");

        PushDispatcher working = new PushDispatcher(service,
                (endpoint, message) -> new PushSender.Result(PushSender.Outcome.ACCEPTED, 201));
        assertThat(working.dispatchPending()).isEqualTo(new PushDispatch.Report(1, 0, 0));
        assertThat(working.dispatchPending()).isEqualTo(new PushDispatch.Report(0, 0, 0));
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

    static final class Subscriptions implements PushSubscriptionRepository {
        private final Map<PushSubscriptionId, PushSubscription> store = new LinkedHashMap<>();

        @Override
        public Optional<PushSubscription> findById(PushSubscriptionId id) {
            return Optional.ofNullable(store.get(id));
        }

        @Override
        public Optional<PushSubscription> findByEndpointUrl(String url) {
            return store.values().stream().filter(s -> s.endpoint().url().equals(url)).findFirst();
        }

        @Override
        public List<PushSubscription> findByMember(MemberId member) {
            return store.values().stream().filter(s -> s.member().equals(member)).toList();
        }

        @Override
        public List<PushSubscription> findActive() {
            return store.values().stream().filter(PushSubscription::isActive).toList();
        }

        @Override
        public void save(PushSubscription subscription) {
            store.put(subscription.id(), subscription);
        }

        @Override
        public void delete(PushSubscriptionId id) {
            store.remove(id);
        }
    }

    static final class Preferences implements PushPreferencesRepository {
        private final Map<MemberId, PushPreferences> store = new HashMap<>();

        @Override
        public Optional<PushPreferences> findByMember(MemberId member) {
            PushPreferences stored = store.get(member);
            return Optional.ofNullable(stored).map(p -> PushPreferences.rehydrate(p.member(), p.categories(),
                    p.quietHours().orElse(null), p.showDetails(), p.version(), p.updatedAt().orElse(null)));
        }

        @Override
        public boolean save(PushPreferences preferences, long previousVersion) {
            PushPreferences stored = store.get(preferences.member());
            if ((stored == null ? 0 : stored.version()) != previousVersion) {
                return false;
            }
            store.put(preferences.member(), preferences);
            return true;
        }
    }

    static final class Deliveries implements PushDeliveryLog {
        private final Map<String, Attempt> store = new HashMap<>();

        @Override
        public Map<NoticeId, Attempt> attempts(PushSubscriptionId subscription, Collection<NoticeId> notices) {
            Map<NoticeId, Attempt> found = new HashMap<>();
            notices.forEach(n -> {
                Attempt attempt = store.get(subscription + "/" + n);
                if (attempt != null) {
                    found.put(n, attempt);
                }
            });
            return found;
        }

        @Override
        public void record(PushSubscriptionId subscription, Collection<NoticeId> notices, PushDeliveryState state,
                Integer statusCode, Instant at) {
            notices.forEach(n -> store.merge(subscription + "/" + n, new Attempt(state, 1, at),
                    (old, next) -> new Attempt(state, old.attempts() + 1, at)));
        }
    }

    static final class Notices implements NoticeRepository {
        private final Map<NoticeId, Notice> store = new LinkedHashMap<>();

        @Override
        public Optional<Notice> findById(NoticeId id) {
            return Optional.ofNullable(store.get(id));
        }

        @Override
        public List<Notice> findByRecipient(MemberId recipient, Set<NoticeKind> kinds, boolean unreadOnly) {
            return List.of();
        }

        @Override
        public List<Notice> findUnreadCreatedSince(MemberId recipient, Instant since) {
            return store.values().stream().filter(n -> n.recipient().equals(recipient) && n.isUnread()
                    && !n.createdAt().isBefore(since)).sorted(Comparator.comparing(Notice::createdAt)).toList();
        }

        @Override
        public Optional<Notice> findByDedupKey(MemberId recipient, String dedupKey) {
            return Optional.empty();
        }

        @Override
        public long countUnread(MemberId recipient, Set<NoticeKind> kinds) {
            return 0;
        }

        @Override
        public void save(Notice notice) {
            store.put(notice.id(), notice);
        }

        @Override
        public boolean saveIfAbsent(Notice notice) {
            save(notice);
            return true;
        }

        @Override
        public int deleteLinkedTo(NoticeLink link) {
            return 0;
        }
    }
}
