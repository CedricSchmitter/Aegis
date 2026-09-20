package dev.aegis.archfixture.badctx.adapters.rest;

/** Fixture: one context publishing a payload whose name another context also claims. */
public final class BadCtxPayloads {
    private BadCtxPayloads() {
    }

    public record ClashingDto(String documentId) {
    }
}
