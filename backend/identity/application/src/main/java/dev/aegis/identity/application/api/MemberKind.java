package dev.aegis.identity.application.api;

/**
 * Public kind of a household member. Persons are what records are about; the assistant account logs in, receives
 * notices and can be responsible for tasks, but is never a document subject or one of the persons an AI run sees.
 */
public enum MemberKind {
    PERSON,
    ASSISTANT
}
