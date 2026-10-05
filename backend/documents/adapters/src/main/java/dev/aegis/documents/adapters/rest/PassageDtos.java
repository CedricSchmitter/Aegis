package dev.aegis.documents.adapters.rest;

import dev.aegis.documents.application.api.PassageQueries.*;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;

/** Transport mapping keeps plain-Java IDs and nullable provenance precise for generated clients. */
public final class PassageDtos {
    private PassageDtos() { }
    public record PassageCoverageDto(long total,long current,long pending,long failed,long stale,long noText,
            long sourceUnavailable,@Schema(nullable=true) Instant oldestVerifiedAt,Instant generatedAt,
            long freshnessSeconds,boolean complete) {
        static PassageCoverageDto from(Coverage c) {
            return new PassageCoverageDto(c.total(),c.current(),c.pending(),c.failed(),c.stale(),c.noText(),
                    c.sourceUnavailable(),c.oldestVerifiedAt(),c.generatedAt(),c.freshnessSeconds(),c.complete());
        }
    }
    public record PassageDto(String documentId,String title,String revision,String sourceFingerprint,String sourceKind,
            String extractionVersion,String chunkVersion,int ordinal,String original,int start,int end,
            @Schema(nullable=true) Integer startPage,@Schema(nullable=true) Integer endPage,Instant verifiedAt) {
        static PassageDto from(Passage p) {
            return new PassageDto(p.documentId().toString(),p.title(),p.revision(),p.sourceFingerprint(),p.sourceKind(),
                    p.extractionVersion(),p.chunkVersion(),p.ordinal(),p.original(),p.start(),p.end(),p.startPage(),p.endPage(),p.verifiedAt());
        }
    }
    public record PassagePageDto(List<PassageDto> passages,@Schema(nullable=true) String nextCursor,PassageCoverageDto coverage) {
        static PassagePageDto from(Page p) {
            return new PassagePageDto(p.passages().stream().map(PassageDto::from).toList(),p.nextCursor(),PassageCoverageDto.from(p.coverage()));
        }
    }
    public record PassageStatusDto(String documentId,State state,@Schema(nullable=true) String revision,
            @Schema(nullable=true) String sourceKind,@Schema(nullable=true) Instant verifiedAt,int attempts,
            @Schema(nullable=true) Instant nextAttemptAt,@Schema(nullable=true) String failureCode) {
        static PassageStatusDto from(Status s) {
            return new PassageStatusDto(s.documentId().toString(),s.state(),s.revision(),s.sourceKind(),s.verifiedAt(),
                    s.attempts(),s.nextAttemptAt(),s.failureCode());
        }
    }
}
