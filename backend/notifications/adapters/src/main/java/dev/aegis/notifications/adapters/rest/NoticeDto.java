package dev.aegis.notifications.adapters.rest;

import dev.aegis.notifications.application.api.NoticeSummary;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.Map;

public record NoticeDto(String id, String kind, Map<String, String> params,
        @Schema(nullable = true) String linkType, @Schema(nullable = true) String linkId, Instant createdAt,
        @Schema(nullable = true) Instant readAt) {
    static NoticeDto from(NoticeSummary s) {
        return new NoticeDto(s.id(), s.kind(), s.params(), s.linkType(), s.linkId(), s.createdAt(), s.readAt());
    }
}
