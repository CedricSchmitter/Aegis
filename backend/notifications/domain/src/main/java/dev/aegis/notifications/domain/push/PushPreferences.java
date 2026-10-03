package dev.aegis.notifications.domain.push;

import dev.aegis.shared.MemberId;
import dev.aegis.shared.StaleVersionException;
import java.time.Instant;
import java.time.LocalTime;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * What a member wants pushed to all of the member's devices: the categories, optional quiet hours and whether the lock
 * screen may show details such as a task title. Without details a push only names the kind of notice. Versioned, so a
 * change made on another device is not overwritten silently.
 */
public final class PushPreferences {
    /** Default for a member who never changed the preferences: everything, quiet at night, no details. */
    public static final QuietHours DEFAULT_QUIET_HOURS = new QuietHours(LocalTime.of(22, 0), LocalTime.of(7, 0));

    private final MemberId member;
    private Set<PushCategory> categories;
    private QuietHours quietHours;
    private boolean showDetails;
    private long version;
    private Instant updatedAt;

    private PushPreferences(MemberId member, Set<PushCategory> categories, QuietHours quietHours, boolean showDetails,
            long version, Instant updatedAt) {
        this.member = Objects.requireNonNull(member);
        this.categories = copy(categories);
        this.quietHours = quietHours;
        this.showDetails = showDetails;
        this.version = version;
        this.updatedAt = updatedAt;
    }

    public static PushPreferences defaults(MemberId member) {
        return new PushPreferences(member, EnumSet.allOf(PushCategory.class), DEFAULT_QUIET_HOURS, false, 0, null);
    }

    public static PushPreferences rehydrate(MemberId member, Set<PushCategory> categories, QuietHours quietHours,
            boolean showDetails, long version, Instant updatedAt) {
        return new PushPreferences(member, categories, quietHours, showDetails, version, updatedAt);
    }

    /**
     * Replaces the preferences.
     *
     * @param expectedVersion the version the member saw
     * @throws StaleVersionException when they changed meanwhile, for example on the member's other device
     */
    public void change(Set<PushCategory> categories, QuietHours quietHours, boolean showDetails, long expectedVersion,
            Instant now) {
        if (expectedVersion != version) {
            throw new StaleVersionException("push preferences", member.toString(), expectedVersion, version);
        }
        this.categories = copy(categories);
        this.quietHours = quietHours;
        this.showDetails = showDetails;
        this.version++;
        this.updatedAt = Objects.requireNonNull(now);
    }

    public boolean wants(PushCategory category) {
        return categories.contains(category);
    }

    public boolean isQuietAt(LocalTime time) {
        return quietHours != null && quietHours.contains(time);
    }

    private static Set<PushCategory> copy(Set<PushCategory> categories) {
        Set<PushCategory> copy = EnumSet.noneOf(PushCategory.class);
        copy.addAll(Objects.requireNonNull(categories, "categories"));
        return copy;
    }

    public MemberId member() {
        return member;
    }

    public Set<PushCategory> categories() {
        return Set.copyOf(categories);
    }

    public Optional<QuietHours> quietHours() {
        return Optional.ofNullable(quietHours);
    }

    public boolean showDetails() {
        return showDetails;
    }

    public long version() {
        return version;
    }

    public Optional<Instant> updatedAt() {
        return Optional.ofNullable(updatedAt);
    }
}
