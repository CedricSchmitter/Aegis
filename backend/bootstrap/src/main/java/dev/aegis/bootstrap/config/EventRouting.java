package dev.aegis.bootstrap.config;

import dev.aegis.contracts.application.ports.ContractEvents;
import dev.aegis.documents.application.ports.DocumentEvents;
import dev.aegis.notifications.application.api.ForgetNotices;
import dev.aegis.notifications.application.api.NoticeDraft;
import dev.aegis.notifications.application.api.PublishNotice;
import dev.aegis.planning.application.ports.PlanningEvents;
import dev.aegis.review.application.api.ForgetReviewCards;
import dev.aegis.shared.ContractId;
import dev.aegis.shared.DocumentId;
import dev.aegis.shared.MemberId;
import dev.aegis.shared.TaskId;
import java.util.Map;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Routes context events to the notice inbox and the review inbox. Runs inside the publishing use case's transaction,
 * so a task assignment and its notice commit together, and a permanently deleted record (ADR-0021) takes its notices
 * and review cards with it. Successful internal steps do not create notices.
 */
@Configuration
public class EventRouting {

    @Bean
    PlanningEvents planningEvents(PublishNotice notices, PurgedRecords purged) {
        return new PlanningEvents() {
            @Override
            public void taskAssigned(TaskId taskId, String title, MemberId assignee, MemberId assignedBy) {
                notices.publish(new NoticeDraft(assignee, "TASK_ASSIGNED", "task-assigned:" + taskId,
                        Map.of("title", title, "byMemberId", assignedBy.toString()), "TASK", taskId.toString()));
            }

            @Override
            public void taskCompleted(TaskId taskId, String title, MemberId owner, MemberId completedBy) {
                notices.publish(new NoticeDraft(owner, "TASK_COMPLETED", "task-completed:" + taskId,
                        Map.of("title", title, "byMemberId", completedBy.toString()), "TASK", taskId.toString()));
            }

            @Override
            public void taskPurged(TaskId taskId) {
                purged.forget("TASK", taskId.toString());
            }
        };
    }

    @Bean
    DocumentEvents documentEvents(PublishNotice notices, PurgedRecords purged) {
        return new DocumentEvents() {
            @Override
            public void documentArchivalFailed(DocumentId id, String title, MemberId uploadedBy, String failureCode) {
                // The inbox shows the failure as its review card; the notice is the delivery trigger for other
                // channels.
                notices.publish(new NoticeDraft(uploadedBy, "DOCUMENT_ARCHIVAL_FAILED", "document-failed:" + id,
                        Map.of("title", title, "code", failureCode), "DOCUMENT", id.toString()));
            }

            @Override
            public void documentPurged(DocumentId id) {
                purged.forget("DOCUMENT", id.toString());
            }
        };
    }

    @Bean
    ContractEvents contractEvents(PurgedRecords purged) {
        return (ContractId id) -> purged.forget("CONTRACT", id.toString());
    }

    @Bean
    PurgedRecords purgedRecords(ObjectProvider<ForgetReviewCards> cards, ObjectProvider<ForgetNotices> notices) {
        return new PurgedRecords(cards, notices);
    }

    /**
     * Removes what describes a permanently deleted record: its review cards, the notices that open it and the notices
     * that announced those cards. The review inbox is resolved lazily because its producers read the very contexts
     * whose events are routed here.
     */
    static final class PurgedRecords {
        private final ObjectProvider<ForgetReviewCards> cards;
        private final ObjectProvider<ForgetNotices> notices;

        PurgedRecords(ObjectProvider<ForgetReviewCards> cards, ObjectProvider<ForgetNotices> notices) {
            this.cards = cards;
            this.notices = notices;
        }

        void forget(String kind, String id) {
            ForgetNotices inbox = notices.getObject();
            for (var card : cards.getObject().forgetRecord(kind, id)) {
                inbox.forgetLinkedTo("REVIEW", card.toString());
            }
            inbox.forgetLinkedTo(kind, id);
        }
    }
}
