package dev.aegis.review.domain;

import dev.aegis.shared.MemberId;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Persisted review workflow reference. Producer contexts remain authoritative for the referenced business data. */
public final class ReviewItem {
    private final UUID id; private final String fingerprint; private final String producer; private final String sourceKind;
    private final String sourceId; private final long sourceVersion; private final String category; private final String reason;
    private final String priority; private final String titleKey; private final Map<String,String> params; private final String linkType;
    private final String linkId; private final List<String> actions; private final Instant createdAt; private Instant updatedAt;
    private ReviewStatus status; private long version; private MemberId decidedBy; private Instant decidedAt;

    private ReviewItem(UUID id, String fingerprint, String producer, String sourceKind, String sourceId, long sourceVersion,
            String category, String reason, String priority, String titleKey, Map<String,String> params, String linkType,
            String linkId, List<String> actions, ReviewStatus status, Instant createdAt, Instant updatedAt, long version,
            MemberId decidedBy, Instant decidedAt) {
        this.id=Objects.requireNonNull(id); this.fingerprint=Objects.requireNonNull(fingerprint); this.producer=Objects.requireNonNull(producer);
        this.sourceKind=Objects.requireNonNull(sourceKind); this.sourceId=Objects.requireNonNull(sourceId); this.sourceVersion=sourceVersion;
        this.category=Objects.requireNonNull(category); this.reason=Objects.requireNonNull(reason); this.priority=Objects.requireNonNull(priority);
        this.titleKey=Objects.requireNonNull(titleKey); this.params=Map.copyOf(params); this.linkType=Objects.requireNonNull(linkType);
        this.linkId=Objects.requireNonNull(linkId); this.actions=List.copyOf(actions); this.status=status; this.createdAt=createdAt;
        this.updatedAt=updatedAt; this.version=version; this.decidedBy=decidedBy; this.decidedAt=decidedAt;
    }
    public static ReviewItem create(UUID id, Candidate c, Instant now) { return new ReviewItem(id,c.fingerprint(),c.producer(),c.sourceKind(),c.sourceId(),c.sourceVersion(),c.category(),c.reason(),c.priority(),c.titleKey(),c.params(),c.linkType(),c.linkId(),c.actions(),ReviewStatus.OPEN,now,now,0,null,null); }
    public static ReviewItem rehydrate(UUID id,String fingerprint,String producer,String sourceKind,String sourceId,long sourceVersion,String category,String reason,String priority,String titleKey,Map<String,String> params,String linkType,String linkId,List<String> actions,ReviewStatus status,Instant createdAt,Instant updatedAt,long version,MemberId decidedBy,Instant decidedAt) { return new ReviewItem(id,fingerprint,producer,sourceKind,sourceId,sourceVersion,category,reason,priority,titleKey,params,linkType,linkId,actions,status,createdAt,updatedAt,version,decidedBy,decidedAt); }
    /** A member resolves or dismisses an open card; a closed card stays unchanged. */
    public void decide(ReviewStatus target, MemberId member, Instant now) {
        if (target != ReviewStatus.RESOLVED && target != ReviewStatus.DISMISSED) throw new IllegalArgumentException("a member resolves or dismisses a card");
        Objects.requireNonNull(member, "deciding member");
        close(target, member, now);
    }
    /** Reconciliation closes an open card whose source no longer reports it. No member decided that, so none is recorded. */
    public void supersede(Instant now) { close(ReviewStatus.SUPERSEDED, null, now); }
    private void close(ReviewStatus target, MemberId member, Instant now) { if (status != ReviewStatus.OPEN) return; status=target; decidedBy=member; decidedAt=now; updatedAt=now; version++; }
    public UUID id(){return id;} public String fingerprint(){return fingerprint;} public String producer(){return producer;} public String sourceKind(){return sourceKind;} public String sourceId(){return sourceId;} public long sourceVersion(){return sourceVersion;} public String category(){return category;} public String reason(){return reason;} public String priority(){return priority;} public String titleKey(){return titleKey;} public Map<String,String> params(){return params;} public String linkType(){return linkType;} public String linkId(){return linkId;} public List<String> actions(){return actions;} public ReviewStatus status(){return status;} public Instant createdAt(){return createdAt;} public Instant updatedAt(){return updatedAt;} public long version(){return version;} public MemberId decidedBy(){return decidedBy;} public Instant decidedAt(){return decidedAt;}
    public record Candidate(String fingerprint,String producer,String sourceKind,String sourceId,long sourceVersion,String category,String reason,String priority,String titleKey,Map<String,String> params,String linkType,String linkId,List<String> actions) { public Candidate { params=Map.copyOf(params); actions=List.copyOf(actions); } }
}
