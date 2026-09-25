package dev.aegis.notifications.adapters.rest;

import dev.aegis.notifications.application.api.NoticeInbox;
import dev.aegis.shared.Actor;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** The member's in-app notice inbox. */
@RestController
@RequestMapping("/api/notices")
@Tag(name = "notices")
public class NoticesController {
    private final NoticeInbox inbox;

    public NoticesController(NoticeInbox inbox) {
        this.inbox = inbox;
    }

    public record UnreadCountResponse(long unread) {
    }

    public record MarkAllReadResponse(int marked) {
    }

    @Operation(operationId = "listNotices",
            summary = "List the current member's messages (notices without a review card), newest first")
    @GetMapping
    public List<NoticeDto> list(Actor actor,
            @RequestParam(name = "unreadOnly", defaultValue = "false") boolean unreadOnly) {
        return inbox.list(actor, unreadOnly).stream().map(NoticeDto::from).toList();
    }

    @Operation(operationId = "unreadNoticeCount", summary = "Number of unread messages")
    @GetMapping("/unread-count")
    public UnreadCountResponse unreadCount(Actor actor) {
        return new UnreadCountResponse(inbox.unreadCount(actor));
    }

    @Operation(operationId = "markNoticeRead", summary = "Mark one notice as read")
    @PostMapping("/{id}/read")
    public NoticeDto markRead(Actor actor, @PathVariable("id") String id) {
        return NoticeDto.from(inbox.markRead(actor, id));
    }

    @Operation(operationId = "markAllNoticesRead", summary = "Mark every unread notice as read")
    @PostMapping("/read-all")
    public MarkAllReadResponse markAllRead(Actor actor) {
        return new MarkAllReadResponse(inbox.markAllRead(actor));
    }

    @Operation(operationId = "markReviewCardNoticesRead",
            summary = "Mark the notices announcing review cards as read when the inbox shows the cards")
    @PostMapping("/review-cards/read")
    public MarkAllReadResponse markReviewCardNoticesRead(Actor actor) {
        return new MarkAllReadResponse(inbox.markReviewCardNoticesRead(actor));
    }
}
