package dev.aegis.notifications.domain;

import java.util.Objects;

/**
 * What the notice opens: a task, document, contract or review card by its stable Aegis id, or the status page with the
 * name of the recurring job it is about.
 */
public record NoticeLink(LinkType type, String id) {
    public enum LinkType {
        TASK,
        DOCUMENT,
        CONTRACT,
        REVIEW,
        STATUS
    }

    public NoticeLink {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(id, "id");
    }
}
