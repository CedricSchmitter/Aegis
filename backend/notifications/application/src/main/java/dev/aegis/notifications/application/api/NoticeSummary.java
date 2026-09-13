package dev.aegis.notifications.application.api;

import java.time.Instant;
import java.util.Map;

/** Read model of a notice; {@code linkType}/{@code linkId} are null when the notice has no target. */
public record NoticeSummary(String id, String kind, Map<String, String> params, String linkType, String linkId,
        Instant createdAt, Instant readAt) {
}
