package dev.aegis.notifications.application.api;

import dev.aegis.shared.Actor;
import java.util.List;

/** The member's own inbox. Read state is per member and never shared. */
public interface NoticeInbox {
    List<NoticeSummary> list(Actor actor, boolean unreadOnly);

    long unreadCount(Actor actor);

    NoticeSummary markRead(Actor actor, String noticeId);

    int markAllRead(Actor actor);
}
