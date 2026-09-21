package dev.aegis.review.application.api;
import dev.aegis.shared.Actor; import java.time.Instant; import java.util.*;
public interface ReviewPortability {
 record Record(UUID id,String fingerprint,String producer,String sourceKind,String sourceId,long sourceVersion,String category,String reason,String priority,String titleKey,Map<String,String> params,String linkType,String linkId,List<String> actions,String status,Instant createdAt,Instant updatedAt,long version,String decidedBy,Instant decidedAt){}
 List<Record> exportAll(Actor actor); void importAll(Actor actor,List<Record> records);
}
