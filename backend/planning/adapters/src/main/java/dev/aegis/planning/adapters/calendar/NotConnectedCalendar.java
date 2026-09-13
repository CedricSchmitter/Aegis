package dev.aegis.planning.adapters.calendar;

import dev.aegis.planning.application.ports.CalendarPort;
import java.time.LocalDate;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Explicit "no calendar connected" adapter. Reports the capability boundary truthfully and never fabricates
 * appointments. A provider adapter replaces this bean once account discovery and tests are done.
 */
@Component
public class NotConnectedCalendar implements CalendarPort {
    @Override
    public Capabilities capabilities() {
        return Capabilities.notConnected();
    }

    @Override
    public List<CalendarEntry> entriesOn(LocalDate date) {
        return List.of();
    }
}
