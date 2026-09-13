package dev.aegis.bootstrap.config;

import dev.aegis.documents.application.ports.DocumentEvents;
import dev.aegis.notifications.application.api.NoticeDraft;
import dev.aegis.notifications.application.api.PublishNotice;
import dev.aegis.planning.application.ports.PlanningEvents;
import dev.aegis.shared.DocumentId;
import dev.aegis.shared.MemberId;
import dev.aegis.shared.TaskId;
import java.util.Map;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Routes context events to the notice inbox. Runs inside the publishing use case's transaction, so a task
 * assignment and its notice commit together. Successful internal steps do not create notices.
 */
@Configuration
public class EventRouting {

    @Bean
    PlanningEvents planningEvents(PublishNotice notices) {
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
        };
    }

    @Bean
    DocumentEvents documentEvents(PublishNotice notices) {
        return new DocumentEvents() {
            @Override
            public void documentArchived(DocumentId id, String title, MemberId uploadedBy) {
                notices.publish(new NoticeDraft(uploadedBy, "DOCUMENT_ARCHIVED", "document-archived:" + id,
                        Map.of("title", title), "DOCUMENT", id.toString()));
            }

            @Override
            public void documentArchivalFailed(DocumentId id, String title, MemberId uploadedBy,
                    String failureCode) {
                notices.publish(new NoticeDraft(uploadedBy, "DOCUMENT_ARCHIVAL_FAILED", "document-failed:" + id,
                        Map.of("title", title, "code", failureCode), "DOCUMENT", id.toString()));
            }
        };
    }
}
