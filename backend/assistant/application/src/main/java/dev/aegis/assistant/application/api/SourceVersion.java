package dev.aegis.assistant.application.api;

/** An input and the version a run analyzes, for example {@code document}, its id and its classification version. */
public record SourceVersion(String kind, String id, long version) {
}
