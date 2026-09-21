package dev.aegis.review.domain;
import java.util.List; import java.util.Optional; import java.util.UUID;
public interface ReviewRepository { Optional<ReviewItem> find(UUID id); Optional<ReviewItem> findByFingerprint(String fingerprint); List<ReviewItem> findAll(); void insert(ReviewItem item); void update(ReviewItem item,long expectedVersion); }
