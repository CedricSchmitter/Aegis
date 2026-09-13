package dev.aegis.archfixture.otherctx.domain;

import dev.aegis.archfixture.otherctx.application.CrossContextService;

/** Fixture: domain depending on application. Must be rejected. */
public class UpwardDependency {
    private final CrossContextService service = new CrossContextService();

    public CrossContextService service() {
        return service;
    }
}
