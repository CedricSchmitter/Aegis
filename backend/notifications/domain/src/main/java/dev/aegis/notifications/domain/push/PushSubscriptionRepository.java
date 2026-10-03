package dev.aegis.notifications.domain.push;

import dev.aegis.shared.MemberId;
import java.util.List;
import java.util.Optional;

/** Persistence of the browsers subscribed to pushes. */
public interface PushSubscriptionRepository {
    Optional<PushSubscription> findById(PushSubscriptionId id);

    Optional<PushSubscription> findByEndpointUrl(String url);

    /** The member's subscriptions, expired ones included, oldest first. */
    List<PushSubscription> findByMember(MemberId member);

    /** Every subscription that has not expired. */
    List<PushSubscription> findActive();

    void save(PushSubscription subscription);

    /** Removes the subscription and its delivery records. */
    void delete(PushSubscriptionId id);
}
