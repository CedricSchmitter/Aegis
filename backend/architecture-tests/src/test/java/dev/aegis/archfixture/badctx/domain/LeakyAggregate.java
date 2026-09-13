package dev.aegis.archfixture.badctx.domain;

import jakarta.persistence.Entity;

/** Fixture: a domain class polluted with a persistence annotation. Must be rejected by the rules. */
@Entity
public class LeakyAggregate {
    private String value;

    public String getValue() {
        return value;
    }
}
