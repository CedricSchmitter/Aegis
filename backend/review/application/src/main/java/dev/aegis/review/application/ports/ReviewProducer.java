package dev.aegis.review.application.ports;
import dev.aegis.review.domain.ReviewItem.Candidate; import dev.aegis.shared.Actor; import java.util.List;
public interface ReviewProducer { String id(); List<Candidate> findCurrent(Actor actor); }
