package dev.aegis.notifications.application.api;

import dev.aegis.shared.Actor;
import java.util.List;

/**
 * The member's own inbox. Read state is per member and never shared. The inbox lists messages: notices that no review
 * card represents. Notices announcing a review card are not listed, because the inbox shows the card.
 */
public interface NoticeInbox {
    /** The member's messages, newest first. */
    List<NoticeSummary> list(Actor actor, boolean unreadOnly);

    /** Unread messages; open review cards are counted by the review inbox. */
    long unreadCount(Actor actor);

    NoticeSummary markRead(Actor actor, String noticeId);

    /** Marks every unread notice of the member read, including those that announce review cards. */
    int markAllRead(Actor actor);

    /**
     * Marks the member's notices that announce review cards read, because the member opened the inbox showing the
     * cards. The cards themselves are not decided.
     */
    int markReviewCardNoticesRead(Actor actor);
}
