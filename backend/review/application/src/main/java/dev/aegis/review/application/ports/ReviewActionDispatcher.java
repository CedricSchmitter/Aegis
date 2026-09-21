package dev.aegis.review.application.ports;
import dev.aegis.review.domain.ReviewItem; import dev.aegis.shared.Actor;
public interface ReviewActionDispatcher { void execute(Actor actor, ReviewItem item, String action); }
