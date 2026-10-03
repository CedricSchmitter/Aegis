package dev.aegis.notifications.domain.push;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.aegis.notifications.domain.NoticeKind;
import dev.aegis.shared.InvalidInputException;
import dev.aegis.shared.MemberId;
import dev.aegis.shared.StaleVersionException;
import java.time.Instant;
import java.time.LocalTime;
import java.util.Base64;
import java.util.EnumSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

class PushDomainTest {
    static final String P256DH;
    static final String AUTH = Base64.getUrlEncoder().withoutPadding().encodeToString(new byte[16]);
    static final String URL = "https://fcm.googleapis.com/fcm/send/abc";

    static {
        byte[] point = new byte[65];
        point[0] = 0x04;
        P256DH = Base64.getUrlEncoder().withoutPadding().encodeToString(point);
    }

    private final Instant t0 = Instant.parse("2026-10-03T10:00:00Z");

    @Test
    void quietHoursAcrossMidnightIncludeTheStartAndExcludeTheEnd() {
        QuietHours night = new QuietHours(LocalTime.of(22, 0), LocalTime.of(7, 0));

        assertThat(night.contains(LocalTime.of(22, 0))).isTrue();
        assertThat(night.contains(LocalTime.of(2, 30))).isTrue();
        assertThat(night.contains(LocalTime.of(7, 0))).isFalse();
        assertThat(night.contains(LocalTime.of(12, 0))).isFalse();

        QuietHours lunch = new QuietHours(LocalTime.of(12, 0), LocalTime.of(13, 0));
        assertThat(lunch.contains(LocalTime.of(12, 30))).isTrue();
        assertThat(lunch.contains(LocalTime.of(13, 0))).isFalse();
        assertThatThrownBy(() -> new QuietHours(LocalTime.NOON, LocalTime.NOON))
                .isInstanceOf(InvalidInputException.class);
    }

    @Test
    void anEndpointNeedsHttpsAndBrowserKeysOfTheRightShape() {
        assertThat(new PushEndpoint(URL, P256DH, AUTH).host()).isEqualTo("fcm.googleapis.com");

        assertThatThrownBy(() -> new PushEndpoint("http://fcm.googleapis.com/x", P256DH, AUTH))
                .isInstanceOf(InvalidInputException.class);
        assertThatThrownBy(() -> new PushEndpoint("https://user@fcm.googleapis.com/x", P256DH, AUTH))
                .isInstanceOf(InvalidInputException.class);
        assertThatThrownBy(() -> new PushEndpoint(URL, AUTH, AUTH)).isInstanceOf(InvalidInputException.class);
        assertThatThrownBy(() -> new PushEndpoint(URL, P256DH, P256DH)).isInstanceOf(InvalidInputException.class);
        assertThatThrownBy(() -> new PushEndpoint(URL, "not base64!", AUTH))
                .isInstanceOf(InvalidInputException.class);
    }

    @Test
    void preferencesRejectAChangeFromAnOutdatedVersion() {
        PushPreferences preferences = PushPreferences.defaults(MemberId.random());
        assertThat(preferences.isQuietAt(LocalTime.of(23, 0))).isTrue();
        assertThat(preferences.categories()).containsExactlyInAnyOrder(PushCategory.values());

        preferences.change(EnumSet.of(PushCategory.TASKS), null, true, 0, t0);

        assertThat(preferences.version()).isEqualTo(1);
        assertThat(preferences.isQuietAt(LocalTime.of(23, 0))).isFalse();
        assertThat(preferences.wants(PushCategory.SYSTEM)).isFalse();
        assertThatThrownBy(() -> preferences.change(Set.of(), null, false, 0, t0))
                .isInstanceOf(StaleVersionException.class);
        assertThat(preferences.wants(PushCategory.TASKS)).isTrue();
    }

    @Test
    void aBrowserSubscribingForAnotherMemberStartsAfresh() {
        MemberId anna = MemberId.random();
        MemberId ben = MemberId.random();
        PushEndpoint endpoint = new PushEndpoint(URL, P256DH, AUTH);
        PushSubscription subscription = PushSubscription.create(anna, endpoint, "Pixel", t0);
        subscription.recordSent(t0.plusSeconds(60));

        subscription.renew(anna, endpoint, "Pixel", t0.plusSeconds(120));
        assertThat(subscription.subscribedAt()).isEqualTo(t0);
        assertThat(subscription.lastSentAt()).contains(t0.plusSeconds(60));

        subscription.renew(ben, endpoint, "Pixel", t0.plusSeconds(180));
        assertThat(subscription.member()).isEqualTo(ben);
        assertThat(subscription.subscribedAt()).isEqualTo(t0.plusSeconds(180));
        assertThat(subscription.lastSentAt()).isEmpty();
    }

    @Test
    void anExpiredSubscriptionIsRevivedOnlyByRenewing() {
        PushSubscription subscription = PushSubscription.create(MemberId.random(),
                new PushEndpoint(URL, P256DH, AUTH), " ", t0);
        assertThat(subscription.deviceLabel()).isEmpty();
        for (int i = 0; i < PushSubscription.FAILING_AFTER; i++) {
            subscription.recordFailure();
        }
        assertThat(subscription.isFailing()).isTrue();

        subscription.expire(t0.plusSeconds(1));
        assertThat(subscription.isActive()).isFalse();

        subscription.renew(subscription.member(), subscription.endpoint(), null, t0.plusSeconds(2));
        assertThat(subscription.isActive()).isTrue();
        assertThat(subscription.isFailing()).isFalse();
        assertThat(subscription.subscribedAt()).isEqualTo(t0.plusSeconds(2));
    }

    @Test
    void everyPublishedKindHasACategoryAndTheRetiredOneHasNone() {
        for (NoticeKind kind : NoticeKind.values()) {
            assertThat(PushCategory.of(kind).isPresent())
                    .as(kind.name()).isEqualTo(kind.placement() != NoticeKind.Placement.RETIRED);
        }
    }
}
