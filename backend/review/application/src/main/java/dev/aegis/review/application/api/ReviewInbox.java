package dev.aegis.review.application.api;
import dev.aegis.shared.Actor; import java.time.Instant; import java.util.List; import java.util.Map; import java.util.UUID;
public interface ReviewInbox {
 record Item(UUID id,String sourceKind,String sourceId,long sourceVersion,String category,String reason,String priority,String titleKey,Map<String,String> params,String linkType,String linkId,List<String> actions,String status,Instant createdAt,Instant updatedAt,long version){}
 List<Item> list(Actor actor,String status,String category); Item dismiss(Actor actor,UUID id,long expectedVersion); Item act(Actor actor,UUID id,String action,long expectedVersion);
}
