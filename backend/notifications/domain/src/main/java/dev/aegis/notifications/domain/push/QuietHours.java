package dev.aegis.notifications.domain.push;

import java.time.LocalTime;
import java.util.Objects;

/**
 * A daily local time span without pushes, for example 22:00 to 07:00. The end is exclusive; a span crossing midnight
 * wraps. Notices arriving during quiet hours wait in the inbox and are pushed together once the span ends.
 */
public record QuietHours(LocalTime start, LocalTime end) {
    public QuietHours {
        Objects.requireNonNull(start, "start");
        Objects.requireNonNull(end, "end");
        if (start.equals(end)) {
            throw new dev.aegis.shared.InvalidInputException("push.quietHours.empty",
                    "quiet hours must start and end at different times");
        }
    }

    public boolean contains(LocalTime time) {
        if (start.isBefore(end)) {
            return !time.isBefore(start) && time.isBefore(end);
        }
        return !time.isBefore(start) || time.isBefore(end);
    }
}
