package dev.aegis.planning.application.ports;

import java.time.LocalDate;
import java.util.List;

/**
 * Calendar capability boundary. Reads may support Today once a provider is connected; writes (create, reschedule,
 * delete) are deliberately not part of this release and must be added behind explicit authorization later.
 */
public interface CalendarPort {

    record Capabilities(boolean connected, String provider, boolean readSupported, boolean writeSupported) {
        public static Capabilities notConnected() {
            return new Capabilities(false, null, false, false);
        }
    }

    record CalendarEntry(String externalId, String title, LocalDate date, boolean allDay) {
    }

    Capabilities capabilities();

    /** Entries for a day; empty when not connected. Never fabricates appointments. */
    List<CalendarEntry> entriesOn(LocalDate date);
}
