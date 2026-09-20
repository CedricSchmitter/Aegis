package dev.aegis.archfixture.otherctx.adapters.rest;

/** Fixture: the colliding counterpart with the same simple name but different fields. */
public final class OtherCtxPayloads {
    private OtherCtxPayloads() {
    }

    public record ClashingDto(String kind, long version) {
    }
}
