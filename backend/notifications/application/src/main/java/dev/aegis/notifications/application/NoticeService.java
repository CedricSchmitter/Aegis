package dev.aegis.notifications.application;

import dev.aegis.notifications.application.api.ForgetNotices;
import dev.aegis.notifications.application.api.NoticeDraft;
import dev.aegis.notifications.application.api.NoticeInbox;
import dev.aegis.notifications.application.api.NoticeSummary;
import dev.aegis.notifications.application.api.PublishNotice;
import dev.aegis.notifications.domain.Notice;
import dev.aegis.notifications.domain.NoticeId;
import dev.aegis.notifications.domain.NoticeKind;
import dev.aegis.notifications.domain.NoticeKind.Placement;
import dev.aegis.notifications.domain.NoticeLink;
import dev.aegis.notifications.domain.NoticeRepository;
import dev.aegis.shared.Actor;
import dev.aegis.shared.HouseholdAccess;
import dev.aegis.shared.InvalidInputException;
import dev.aegis.shared.NotAuthorizedException;
import dev.aegis.shared.NotFoundException;
import java.time.Clock;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * Inbox use cases: publishing with deduplication and per-member read state. Only messages are listed and counted;
 * notices announcing a review card are published but shown as the card.
 */
public class NoticeService implements PublishNotice, NoticeInbox, ForgetNotices {
    private static final Set<NoticeKind> MESSAGES = NoticeKind.placedAs(Placement.MESSAGE);
    private static final Set<NoticeKind> REVIEW_CARDS = NoticeKind.placedAs(Placement.REVIEW_CARD);

    private final NoticeRepository notices;
    private final HouseholdAccess access;
    private final Clock clock;

    public NoticeService(NoticeRepository notices, HouseholdAccess access, Clock clock) {
        this.notices = notices;
        this.access = access;
        this.clock = clock;
    }

    @Override
    public int forgetLinkedTo(String linkType, String linkId) {
        return notices.deleteLinkedTo(new NoticeLink(NoticeLink.LinkType.valueOf(linkType), linkId));
    }

    @Override
    public PublishOutcome publish(NoticeDraft draft) {
        NoticeKind kind;
        try {
            kind = NoticeKind.valueOf(draft.kind());
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new InvalidInputException("notice.kind.invalid", "Unknown notice kind " + draft.kind());
        }
        if (kind.placement() == Placement.RETIRED) {
            throw new InvalidInputException("notice.kind.retired", "Notice kind " + kind + " is no longer published");
        }
        if (notices.findByDedupKey(draft.recipient(), draft.dedupKey()).isPresent()) {
            return PublishOutcome.DEDUPLICATED;
        }
        NoticeLink link = draft.linkType() == null ? null
                : new NoticeLink(NoticeLink.LinkType.valueOf(draft.linkType()), draft.linkId());
        Notice notice = Notice.create(draft.recipient(), kind, draft.dedupKey(), draft.params(), link,
                clock.instant());
        return notices.saveIfAbsent(notice) ? PublishOutcome.PUBLISHED : PublishOutcome.DEDUPLICATED;
    }

    @Override
    public List<NoticeSummary> list(Actor actor, boolean unreadOnly) {
        access.requireMember(actor);
        return notices.findByRecipient(actor.memberId(), MESSAGES, unreadOnly).stream()
                .map(NoticeService::summarize).toList();
    }

    @Override
    public long unreadCount(Actor actor) {
        access.requireMember(actor);
        return notices.countUnread(actor.memberId(), MESSAGES);
    }

    @Override
    public NoticeSummary markRead(Actor actor, String noticeId) {
        access.requireMember(actor);
        Notice notice = notices.findById(NoticeId.of(noticeId))
                .orElseThrow(() -> new NotFoundException("notice", noticeId));
        if (!notice.recipient().equals(actor.memberId())) {
            throw new NotAuthorizedException("notice belongs to another member");
        }
        notice.markRead(clock.instant());
        notices.save(notice);
        return summarize(notice);
    }

    @Override
    public int markAllRead(Actor actor) {
        access.requireMember(actor);
        return markUnreadRead(actor, EnumSet.allOf(NoticeKind.class));
    }

    @Override
    public int markReviewCardNoticesRead(Actor actor) {
        access.requireMember(actor);
        return markUnreadRead(actor, REVIEW_CARDS);
    }

    private int markUnreadRead(Actor actor, Set<NoticeKind> kinds) {
        int count = 0;
        for (Notice notice : notices.findByRecipient(actor.memberId(), kinds, true)) {
            notice.markRead(clock.instant());
            notices.save(notice);
            count++;
        }
        return count;
    }

    static NoticeSummary summarize(Notice notice) {
        return new NoticeSummary(notice.id().toString(), notice.kind().name(), notice.params(),
                notice.link().map(l -> l.type().name()).orElse(null), notice.link().map(NoticeLink::id).orElse(null),
                notice.createdAt(), notice.readAt().orElse(null));
    }
}
