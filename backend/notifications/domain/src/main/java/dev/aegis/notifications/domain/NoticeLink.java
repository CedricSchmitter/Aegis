package dev.aegis.notifications.domain;

import java.util.Objects;

/** Record the notice opens: a task or a document, identified by its stable Aegis id. */
public record NoticeLink(LinkType type, String id) {
    public enum LinkType {
        TASK,
        DOCUMENT,
        CONTRACT,
        REVIEW
    }

    public NoticeLink {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(id, "id");
    }
}
