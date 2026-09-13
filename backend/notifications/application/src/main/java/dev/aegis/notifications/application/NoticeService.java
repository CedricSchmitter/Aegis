package dev.aegis.notifications.application;

import dev.aegis.notifications.application.api.NoticeDraft;
import dev.aegis.notifications.application.api.NoticeInbox;
import dev.aegis.notifications.application.api.NoticeSummary;
import dev.aegis.notifications.application.api.PublishNotice;
import dev.aegis.notifications.domain.Notice;
import dev.aegis.notifications.domain.NoticeId;
import dev.aegis.notifications.domain.NoticeKind;
import dev.aegis.notifications.domain.NoticeLink;
import dev.aegis.notifications.domain.NoticeRepository;
import dev.aegis.shared.Actor;
import dev.aegis.shared.HouseholdAccess;
import dev.aegis.shared.InvalidInputException;
import dev.aegis.shared.NotAuthorizedException;
import dev.aegis.shared.NotFoundException;
import java.time.Clock;
import java.util.List;

/** Inbox use cases: publishing with deduplication and per-member read state. */
public class NoticeService implements PublishNotice, NoticeInbox {
    private final NoticeRepository notices;
    private final HouseholdAccess access;
    private final Clock clock;

    public NoticeService(NoticeRepository notices, HouseholdAccess access, Clock clock) {
        this.notices = notices;
        this.access = access;
        this.clock = clock;
    }

    @Override
    public PublishOutcome publish(NoticeDraft draft) {
        if (notices.findUnreadByDedupKey(draft.recipient(), draft.dedupKey()).isPresent()) {
            return PublishOutcome.DEDUPLICATED;
        }
        NoticeKind kind;
        try {
            kind = NoticeKind.valueOf(draft.kind());
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new InvalidInputException("notice.kind.invalid", "Unknown notice kind " + draft.kind());
        }
        NoticeLink link = draft.linkType() == null ? null
                : new NoticeLink(NoticeLink.LinkType.valueOf(draft.linkType()), draft.linkId());
        notices.save(Notice.create(draft.recipient(), kind, draft.dedupKey(), draft.params(), link, clock.instant()));
        return PublishOutcome.PUBLISHED;
    }

    @Override
    public List<NoticeSummary> list(Actor actor, boolean unreadOnly) {
        access.requireMember(actor);
        return notices.findByRecipient(actor.memberId(), unreadOnly).stream().map(NoticeService::summarize).toList();
    }

    @Override
    public long unreadCount(Actor actor) {
        access.requireMember(actor);
        return notices.countUnread(actor.memberId());
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
        int count = 0;
        for (Notice notice : notices.findByRecipient(actor.memberId(), true)) {
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
