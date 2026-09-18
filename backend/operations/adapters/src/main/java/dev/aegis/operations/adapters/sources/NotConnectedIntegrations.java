package dev.aegis.operations.adapters.sources;

import dev.aegis.operations.application.api.IntegrationStatus;
import dev.aegis.operations.application.ports.IntegrationStatusSource;
import dev.aegis.shared.Actor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Integrations that are deliberately not connected in the first runnable test. They are listed so the status page
 * says so explicitly instead of implying live behavior.
 */
@Configuration
public class NotConnectedIntegrations {

    private static IntegrationStatusSource fixed(int order, String name, String detail) {
        return new IntegrationStatusSource() {
            @Override
            public int order() {
                return order;
            }

            @Override
            public IntegrationStatus check(Actor actor) {
                return IntegrationStatus.notConfigured(name, detail);
            }
        };
    }

    @Bean
    IntegrationStatusSource calendarStatusSource() {
        return fixed(40, "calendar", "no calendar provider connected; reads and writes are deferred");
    }

    @Bean
    IntegrationStatusSource mailStatusSource() {
        return fixed(50, "mail", "no mailbox connected");
    }

    @Bean
    IntegrationStatusSource webPushStatusSource() {
        return fixed(70, "webPush", "in-app notices only; phone push requires HTTPS and device tests");
    }

    @Bean
    IntegrationStatusSource telegramStatusSource() {
        return fixed(80, "telegram", "optional future delivery adapter; not configured");
    }
}
