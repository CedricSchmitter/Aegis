package dev.aegis.review.domain;
import java.util.Collection; import java.util.List; import java.util.Optional; import java.util.Set; import java.util.UUID;
public interface ReviewRepository {
 Optional<ReviewItem> find(UUID id); Optional<ReviewItem> findByFingerprint(String fingerprint); List<ReviewItem> findAll();
 /** Open cards only; reconciliation supersedes those whose source no longer reports them. */
 List<ReviewItem> findOpen();
 /** The given fingerprints that already have a card, whatever its status. */
 Set<String> findExistingFingerprints(Collection<String> fingerprints);
 /** Inserts the card unless one with its id or fingerprint exists, also one a concurrent transaction is inserting; true when this call inserted it. */
 boolean insertIfAbsent(ReviewItem item);
 void update(ReviewItem item,long expectedVersion);
}
