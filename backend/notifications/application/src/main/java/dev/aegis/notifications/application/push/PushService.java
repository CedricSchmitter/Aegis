package dev.aegis.notifications.application.push;

import dev.aegis.notifications.application.api.PushSettings;
import dev.aegis.notifications.domain.Notice;
import dev.aegis.notifications.domain.NoticeId;
import dev.aegis.notifications.domain.NoticeLink;
import dev.aegis.notifications.domain.NoticeRepository;
import dev.aegis.notifications.domain.push.PushCategory;
import dev.aegis.notifications.domain.push.PushDeliveryLog;
import dev.aegis.notifications.domain.push.PushDeliveryState;
import dev.aegis.notifications.domain.push.PushEndpoint;
import dev.aegis.notifications.domain.push.PushPreferences;
import dev.aegis.notifications.domain.push.PushPreferencesRepository;
import dev.aegis.notifications.domain.push.PushSubscription;
import dev.aegis.notifications.domain.push.PushSubscriptionId;
import dev.aegis.notifications.domain.push.PushSubscriptionRepository;
import dev.aegis.notifications.domain.push.QuietHours;
import dev.aegis.shared.Actor;
import dev.aegis.shared.HouseholdAccess;
import dev.aegis.shared.InvalidInputException;
import dev.aegis.shared.MemberId;
import dev.aegis.shared.NotFoundException;
import dev.aegis.shared.ProductLocale;
import dev.aegis.shared.StaleVersionException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Push subscriptions, preferences and the decision what to push (issue #28). A notice is pushed to each of the
 * recipient's active devices at most once: only while it is unread, not older than the catch-up window, in a category
 * the member wants and outside the member's quiet hours. Several notices due at the same time, for example after
 * quiet hours or downtime, become one summary push instead of a burst. A failed send is retried a few times with a
 * growing pause; the inbox keeps every notice regardless.
 */
public class PushService implements PushSettings, PushLedger {
    /** Attempts per notice and device before the push is given up; the notice stays in the inbox. */
    public static final int MAX_ATTEMPTS = 5;
    /** Pause before the n-th retry: n times this. */
    static final Duration RETRY_STEP = Duration.ofMinutes(2);
    static final String SUMMARY_TAG = "aegis-inbox";

    private final PushSubscriptionRepository subscriptions;
    private final PushPreferencesRepository preferences;
    private final PushDeliveryLog deliveries;
    private final NoticeRepository notices;
    private final PushServerKey serverKey;
    private final PushTexts texts;
    private final RecipientLocales locales;
    private final PushServiceHosts hosts;
    private final HouseholdAccess access;
    private final Duration catchUpWindow;
    private final ZoneId zone;
    private final Clock clock;

    public PushService(PushSubscriptionRepository subscriptions, PushPreferencesRepository preferences,
            PushDeliveryLog deliveries, NoticeRepository notices, PushServerKey serverKey, PushTexts texts,
            RecipientLocales locales, PushServiceHosts hosts, HouseholdAccess access, Duration catchUpWindow,
            ZoneId zone, Clock clock) {
        if (catchUpWindow.isNegative() || catchUpWindow.isZero()) {
            throw new IllegalArgumentException("the push catch-up window must be positive");
        }
        this.subscriptions = subscriptions;
        this.preferences = preferences;
        this.deliveries = deliveries;
        this.notices = notices;
        this.serverKey = serverKey;
        this.texts = texts;
        this.locales = locales;
        this.hosts = hosts;
        this.access = access;
        this.catchUpWindow = catchUpWindow;
        this.zone = zone;
        this.clock = clock;
    }

    @Override
    public PushConfig config(Actor actor) {
        access.requireMember(actor);
        return serverKey.publicKey().map(key -> new PushConfig(true, key)).orElse(new PushConfig(false, null));
    }

    @Override
    public PushDevice subscribe(Actor actor, Subscribe request) {
        access.requireMember(actor);
        if (request == null || request.endpoint() == null || request.p256dh() == null || request.auth() == null) {
            throw new InvalidInputException("push.subscription.invalid", "endpoint and keys are required");
        }
        PushEndpoint endpoint = new PushEndpoint(request.endpoint(), request.p256dh(), request.auth());
        if (!hosts.allows(endpoint.host())) {
            throw new InvalidInputException("push.endpoint.notAllowed",
                    "push endpoint host is not a known push service");
        }
        Instant now = clock.instant();
        PushSubscription subscription = subscriptions.findByEndpointUrl(endpoint.url()).map(existing -> {
            existing.renew(actor.memberId(), endpoint, request.deviceLabel(), now);
            return existing;
        }).orElseGet(() -> PushSubscription.create(actor.memberId(), endpoint, request.deviceLabel(), now));
        subscriptions.save(subscription);
        return view(subscription);
    }

    @Override
    public List<PushDevice> devices(Actor actor) {
        access.requireMember(actor);
        return subscriptions.findByMember(actor.memberId()).stream().map(PushService::view).toList();
    }

    @Override
    public void removeDevice(Actor actor, String deviceId) {
        access.requireMember(actor);
        PushSubscription subscription = own(actor, deviceId);
        subscriptions.delete(subscription.id());
    }

    @Override
    public Preferences preferences(Actor actor) {
        access.requireMember(actor);
        return view(preferencesOf(actor.memberId()));
    }

    @Override
    public Preferences changePreferences(Actor actor, Preferences request) {
        access.requireMember(actor);
        if (request == null || request.categories() == null) {
            throw new InvalidInputException("validation.failed", "categories are required");
        }
        Set<PushCategory> categories = EnumSet.noneOf(PushCategory.class);
        for (String category : request.categories()) {
            try {
                categories.add(PushCategory.valueOf(category));
            } catch (IllegalArgumentException | NullPointerException e) {
                throw new InvalidInputException("push.category.invalid", "Unknown push category " + category);
            }
        }
        if ((request.quietStart() == null) != (request.quietEnd() == null)) {
            throw new InvalidInputException("push.quietHours.incomplete", "quiet hours need a start and an end");
        }
        QuietHours quiet = request.quietStart() == null ? null : new QuietHours(request.quietStart(),
                request.quietEnd());
        PushPreferences current = preferencesOf(actor.memberId());
        long previous = current.version();
        current.change(categories, quiet, request.showDetails(), request.version(), clock.instant());
        if (!preferences.save(current, previous)) {
            throw new StaleVersionException("push preferences", actor.memberId().toString(), request.version(),
                    previous + 1);
        }
        return view(current);
    }

    @Override
    public List<PendingPush> pending() {
        if (serverKey.publicKey().isEmpty()) {
            return List.of();
        }
        Instant now = clock.instant();
        List<PendingPush> pending = new ArrayList<>();
        for (PushSubscription subscription : subscriptions.findActive()) {
            PushPreferences wanted = preferencesOf(subscription.member());
            if (wanted.isQuietAt(now.atZone(zone).toLocalTime())) {
                continue;
            }
            Instant windowStart = now.minus(catchUpWindow);
            Instant since = subscription.subscribedAt().isAfter(windowStart) ? subscription.subscribedAt()
                    : windowStart;
            List<Notice> candidates = notices.findUnreadCreatedSince(subscription.member(), since).stream()
                    .filter(notice -> PushCategory.of(notice.kind()).filter(wanted::wants).isPresent()).toList();
            if (candidates.isEmpty()) {
                continue;
            }
            Map<NoticeId, PushDeliveryLog.Attempt> attempts = deliveries.attempts(subscription.id(),
                    candidates.stream().map(Notice::id).toList());
            List<Notice> due = candidates.stream().filter(notice -> isDue(attempts.get(notice.id()), now))
                    .sorted(Comparator.comparing(Notice::createdAt)).toList();
            if (!due.isEmpty()) {
                pending.add(new PendingPush(subscription.id(), subscription.endpoint(),
                        due.stream().map(Notice::id).toList(), message(subscription, wanted, due)));
            }
        }
        return pending;
    }

    @Override
    public PendingPush testPush(Actor actor, String deviceId) {
        access.requireMember(actor);
        PushSubscription subscription = own(actor, deviceId);
        PushTexts.Text text = texts.test(locales.localeOf(actor.memberId()));
        return new PendingPush(subscription.id(), subscription.endpoint(), List.of(), new PushMessage(text.title(),
                text.body(), "/settings", "aegis-test", null, Duration.ofMinutes(10)));
    }

    @Override
    public void recordOutcome(PendingPush push, PushSender.Result result) {
        PushSubscription subscription = subscriptions.findById(push.subscription()).orElse(null);
        if (subscription == null || result.outcome() == PushSender.Outcome.NOT_CONFIGURED) {
            return;
        }
        Instant now = clock.instant();
        PushDeliveryState state = switch (result.outcome()) {
            case ACCEPTED -> {
                subscription.recordSent(now);
                yield PushDeliveryState.SENT;
            }
            case GONE -> {
                subscription.expire(now);
                yield PushDeliveryState.EXPIRED;
            }
            case REJECTED, UNAVAILABLE, NOT_CONFIGURED -> {
                subscription.recordFailure();
                yield PushDeliveryState.FAILED;
            }
        };
        subscriptions.save(subscription);
        if (!push.notices().isEmpty()) {
            deliveries.record(subscription.id(), push.notices(), state, result.statusCode(), now);
        }
    }

    private static boolean isDue(PushDeliveryLog.Attempt attempt, Instant now) {
        if (attempt == null) {
            return true;
        }
        if (attempt.state() != PushDeliveryState.FAILED || attempt.attempts() >= MAX_ATTEMPTS) {
            return false;
        }
        return !now.isBefore(attempt.lastAttemptAt().plus(RETRY_STEP.multipliedBy(attempt.attempts())));
    }

    private PushMessage message(PushSubscription subscription, PushPreferences wanted, List<Notice> due) {
        ProductLocale locale = locales.localeOf(subscription.member());
        if (due.size() == 1) {
            Notice notice = due.getFirst();
            PushTexts.Text text = texts.notice(locale, notice.kind(), notice.params(), wanted.showDetails());
            return new PushMessage(text.title(), text.body(), path(notice), "notice-" + notice.id(),
                    topic(subscription, due), catchUpWindow);
        }
        PushTexts.Text text = texts.summary(locale, due.size());
        return new PushMessage(text.title(), text.body(), "/inbox", SUMMARY_TAG, topic(subscription, due),
                catchUpWindow);
    }

    /** Where a tap leads; mirrors the targets of the in-app inbox. */
    static String path(Notice notice) {
        if (notice.link().isEmpty()) {
            return "/inbox";
        }
        NoticeLink link = notice.link().get();
        String id = java.net.URLEncoder.encode(link.id(), StandardCharsets.UTF_8);
        return switch (link.type()) {
            case TASK -> "/tasks/" + id;
            case DOCUMENT -> "/documents/" + id;
            case CONTRACT -> "/contracts/" + id;
            case REVIEW -> "/inbox?item=" + id;
            case STATUS -> "/status";
        };
    }

    /**
     * Push service topic (RFC 8030, at most 32 base64url characters): a resend of the same message replaces the copy
     * that still waits for an offline device instead of queuing a second one.
     */
    private static String topic(PushSubscription subscription, List<Notice> due) {
        String key = subscription.id() + ":" + due.stream().map(n -> n.id().toString())
                .collect(Collectors.joining(","));
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(key.getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(digest).substring(0, 32);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private PushPreferences preferencesOf(MemberId member) {
        return preferences.findByMember(member).orElseGet(() -> PushPreferences.defaults(member));
    }

    private PushSubscription own(Actor actor, String deviceId) {
        PushSubscription subscription = subscriptions.findById(PushSubscriptionId.of(deviceId))
                .orElseThrow(() -> new NotFoundException("push device", deviceId));
        if (!subscription.member().equals(actor.memberId())) {
            // Another member's device is reported as missing rather than revealing it exists.
            throw new NotFoundException("push device", deviceId);
        }
        return subscription;
    }

    private static PushDevice view(PushSubscription subscription) {
        String state = !subscription.isActive() ? "EXPIRED" : subscription.isFailing() ? "FAILING" : "ACTIVE";
        return new PushDevice(subscription.id().toString(), subscription.deviceLabel().orElse(null), state,
                subscription.createdAt(), subscription.lastSentAt().orElse(null));
    }

    private static Preferences view(PushPreferences preferences) {
        Set<String> categories = preferences.categories().stream().map(Enum::name)
                .collect(Collectors.toCollection(java.util.TreeSet::new));
        return new Preferences(categories, preferences.quietHours().map(QuietHours::start).orElse(null),
                preferences.quietHours().map(QuietHours::end).orElse(null), preferences.showDetails(),
                preferences.version());
    }
}
