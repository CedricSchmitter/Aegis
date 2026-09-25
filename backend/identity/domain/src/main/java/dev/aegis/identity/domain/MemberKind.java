package dev.aegis.identity.domain;

/**
 * Whether a household member is a person or the household's assistant account. Both kinds log in, read the shared
 * household, receive notices and can be responsible for tasks; only persons are what a document is about.
 */
public enum MemberKind {
    /** A person of the household, for example Lisa or Cedric. */
    PERSON,
    /** The household's technical assistant account; a base for delegating tasks to an assistant later. */
    ASSISTANT
}
