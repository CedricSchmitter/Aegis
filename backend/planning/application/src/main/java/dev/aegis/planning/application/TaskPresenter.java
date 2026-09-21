package dev.aegis.planning.application;

import dev.aegis.documents.application.api.DocumentReference;
import dev.aegis.documents.application.api.DocumentReferenceQuery;
import dev.aegis.planning.application.api.TaskExportRecord;
import dev.aegis.planning.application.api.TaskSummary;
import dev.aegis.planning.application.api.TaskSummary.SourceView;
import dev.aegis.planning.application.api.TaskSummary.CancellationReviewView;
import dev.aegis.planning.domain.Task;
import dev.aegis.planning.domain.TaskSource;

/** Read-model mapping; resolves source titles through the documents public contract. */
public class TaskPresenter {
    private final DocumentReferenceQuery documents;

    public TaskPresenter(DocumentReferenceQuery documents) {
        this.documents = documents;
    }

    public TaskSummary summarize(Task task) {
        SourceView source = task.source().map(this::sourceView).orElse(null);
        CancellationReviewView cancellation = task.cancellationReview().map(review -> new CancellationReviewView(
                review.contractId(), review.contractName(), review.contractEnd(), review.cancellationDeadline(),
                review.reminderDate(), review.contractVersion())).orElse(null);
        return new TaskSummary(task.id(), task.title(), task.note(), task.owner().orElse(null), task.status().name(),
                task.dueDate().orElse(null), task.plannedDate().orElse(null), source, cancellation, task.createdBy(),
                task.createdAt(), task.updatedAt(), task.completedAt().orElse(null),
                task.completedBy().orElse(null), task.version());
    }

    public TaskExportRecord exportRecord(Task task) {
        var cancellation = task.cancellationReview().orElse(null);
        return new TaskExportRecord(task.id(), task.title(), task.note(), task.owner().orElse(null),
                task.status().name(), task.dueDate().orElse(null), task.plannedDate().orElse(null),
                task.source().map(TaskSource::documentId).orElse(null), task.createdBy(), task.createdAt(),
                task.updatedAt(), task.completedAt().orElse(null), task.completedBy().orElse(null),
                cancellation == null ? null : cancellation.contractId(),
                cancellation == null ? null : cancellation.contractName(),
                cancellation == null ? null : cancellation.contractEnd(),
                cancellation == null ? null : cancellation.cancellationDeadline(),
                cancellation == null ? null : cancellation.reminderDate(),
                cancellation == null ? null : cancellation.contractVersion());
    }

    private SourceView sourceView(TaskSource source) {
        return new SourceView(source.documentId(),
                documents.findReference(source.documentId()).map(DocumentReference::title).orElse(null));
    }
}
