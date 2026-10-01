package dev.aegis.shared;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/**
 * A record a member moved to the trash (issue #48, ADR-0010). The record keeps its identity and every value;
 * ordinary views hide it until a member restores it. Documents, tasks and contracts share this concept so the three
 * contexts make the same promise.
 *
 * <p>{@code restorableUntil} is fixed when the record is trashed: it stays restorable at least until then, even if
 * the configured window changes later. A record also stays restorable after that instant: only a member's explicit
 * permanent deletion removes it (issue #85, ADR-0021), never the passing of this date.
 */
public record Trash(MemberId trashedBy, Instant trashedAt, Instant restorableUntil) {

    public Trash {
        Objects.requireNonNull(trashedBy, "trashed by");
        Objects.requireNonNull(trashedAt, "trashed at");
        Objects.requireNonNull(restorableUntil, "restorable until");
        if (restorableUntil.isBefore(trashedAt)) {
            throw new IllegalArgumentException("restorable until must not precede the trash time");
        }
    }

    /** Starts the recovery window now. */
    public static Trash begin(MemberId by, Instant now, RecoveryWindow window) {
        return new Trash(by, now, now.plus(window.duration()));
    }

    /**
     * How long a trashed record is guaranteed to stay restorable. Configured once for the household
     * ({@code aegis.trash.recovery-window}); the owner chose 30 days.
     */
    public record RecoveryWindow(Duration duration) {
        public static final RecoveryWindow DEFAULT = new RecoveryWindow(Duration.ofDays(30));

        public RecoveryWindow {
            Objects.requireNonNull(duration, "recovery window");
            if (duration.compareTo(Duration.ofDays(1)) < 0) {
                throw new IllegalArgumentException("the recovery window must be at least one day");
            }
        }
    }
}
