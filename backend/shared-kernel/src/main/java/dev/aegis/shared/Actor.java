package dev.aegis.shared;

import java.util.Objects;

/**
 * The authenticated household member on whose behalf a use case runs. Inbound adapters create it from the
 * verified session; application services still verify membership through {@link HouseholdAccess}.
 */
public record Actor(MemberId memberId) {
    public Actor {
        Objects.requireNonNull(memberId, "member id");
    }
}
