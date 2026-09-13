package dev.aegis.archfixture.otherctx.application;

import dev.aegis.archfixture.badctx.domain.LeakyAggregate;

/** Fixture: reaches into another context's domain instead of its application.api. Must be rejected. */
public class CrossContextService {
    public String read(LeakyAggregate aggregate) {
        return aggregate.getValue();
    }
}
