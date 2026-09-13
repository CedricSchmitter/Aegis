package dev.aegis.notifications.application.api;

/** Public contract for other contexts (through the composition root) to raise notices. */
public interface PublishNotice {
    enum PublishOutcome {
        PUBLISHED,
        DEDUPLICATED
    }

    PublishOutcome publish(NoticeDraft draft);
}
