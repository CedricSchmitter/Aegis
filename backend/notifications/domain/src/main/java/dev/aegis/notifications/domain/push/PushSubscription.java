package dev.aegis.notifications.domain.push;

import dev.aegis.shared.MemberId;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * One browser on one device that agreed to receive pushes for a member. The browser owns the endpoint: when another
 * member signs in on the same browser and subscribes, the subscription moves to that member. A push service answering
 * that the endpoint is gone expires it for good; the browser then has to subscribe again.
 */
public final class PushSubscription {
    /** Consecutive failed sends after which a subscription counts as failing on the settings page. */
    public static final int FAILING_AFTER = 3;

    private final PushSubscriptionId id;
    private MemberId member;
    private PushEndpoint endpoint;
    private String deviceLabel;
    private final Instant createdAt;
    private Instant subscribedAt;
    private Instant lastSentAt;
    private int consecutiveFailures;
    private Instant expiredAt;

    private PushSubscription(PushSubscriptionId id, MemberId member, PushEndpoint endpoint, String deviceLabel,
            Instant createdAt, Instant subscribedAt, Instant lastSentAt, int consecutiveFailures, Instant expiredAt) {
        this.id = Objects.requireNonNull(id);
        this.member = Objects.requireNonNull(member);
        this.endpoint = Objects.requireNonNull(endpoint);
        this.deviceLabel = label(deviceLabel);
        this.createdAt = Objects.requireNonNull(createdAt);
        this.subscribedAt = Objects.requireNonNull(subscribedAt);
        this.lastSentAt = lastSentAt;
        this.consecutiveFailures = consecutiveFailures;
        this.expiredAt = expiredAt;
    }

    public static PushSubscription create(MemberId member, PushEndpoint endpoint, String deviceLabel, Instant now) {
        return new PushSubscription(PushSubscriptionId.random(), member, endpoint, deviceLabel, now, now, null, 0,
                null);
    }

    public static PushSubscription rehydrate(PushSubscriptionId id, MemberId member, PushEndpoint endpoint,
            String deviceLabel, Instant createdAt, Instant subscribedAt, Instant lastSentAt, int consecutiveFailures,
            Instant expiredAt) {
        return new PushSubscription(id, member, endpoint, deviceLabel, createdAt, subscribedAt, lastSentAt,
                consecutiveFailures, expiredAt);
    }

    /**
     * The browser subscribed again with this endpoint, possibly for another member or with new keys. Pushes start
     * from now: notices published before are not delivered to a member who was not subscribed here when they arrived.
     */
    public void renew(MemberId member, PushEndpoint endpoint, String deviceLabel, Instant now) {
        boolean newMember = !this.member.equals(member);
        this.member = Objects.requireNonNull(member);
        this.endpoint = Objects.requireNonNull(endpoint);
        this.deviceLabel = label(deviceLabel);
        if (newMember || expiredAt != null) {
            subscribedAt = now;
            lastSentAt = null;
        }
        consecutiveFailures = 0;
        expiredAt = null;
    }

    public void recordSent(Instant now) {
        lastSentAt = Objects.requireNonNull(now);
        consecutiveFailures = 0;
    }

    public void recordFailure() {
        consecutiveFailures++;
    }

    /** The push service no longer knows the endpoint. */
    public void expire(Instant now) {
        if (expiredAt == null) {
            expiredAt = Objects.requireNonNull(now);
        }
    }

    public boolean isActive() {
        return expiredAt == null;
    }

    public boolean isFailing() {
        return consecutiveFailures >= FAILING_AFTER;
    }

    private static String label(String deviceLabel) {
        if (deviceLabel == null || deviceLabel.isBlank()) {
            return null;
        }
        String stripped = deviceLabel.strip();
        return stripped.length() > 80 ? stripped.substring(0, 80) : stripped;
    }

    public PushSubscriptionId id() {
        return id;
    }

    public MemberId member() {
        return member;
    }

    public PushEndpoint endpoint() {
        return endpoint;
    }

    public Optional<String> deviceLabel() {
        return Optional.ofNullable(deviceLabel);
    }

    public Instant createdAt() {
        return createdAt;
    }

    /** Since when this browser receives pushes for its current member. */
    public Instant subscribedAt() {
        return subscribedAt;
    }

    public Optional<Instant> lastSentAt() {
        return Optional.ofNullable(lastSentAt);
    }

    public int consecutiveFailures() {
        return consecutiveFailures;
    }

    public Optional<Instant> expiredAt() {
        return Optional.ofNullable(expiredAt);
    }
}
