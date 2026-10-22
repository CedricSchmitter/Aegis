package dev.aegis.documents.domain.history;

import dev.aegis.shared.ChangeActor;
import dev.aegis.shared.DocumentId;
import dev.aegis.shared.FieldChange;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.UUID;

/**
 * One recorded change of a document (ADR-0029). {@code sequence} orders the entries of the household; it is assigned
 * when the entry is stored. {@code undoOf} names the change an undo reversed.
 */
public record DocumentChange(UUID id, DocumentId documentId, long sequence, Instant occurredAt, ChangeActor actor,
        DocumentAction action, List<FieldChange> changes, UUID undoOf) {

    public DocumentChange {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(documentId, "document");
        Objects.requireNonNull(occurredAt, "occurred at");
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(action, "action");
        changes = List.copyOf(changes);
        if ((action == DocumentAction.UNDONE) != (undoOf != null)) {
            throw new IllegalArgumentException("only an undo names the change it reversed");
        }
    }

    public static DocumentChange record(DocumentId documentId, Instant now, ChangeActor actor,
            DocumentAction action, List<FieldChange> changes) {
        return new DocumentChange(UUID.randomUUID(), documentId, 0, now, actor, action, changes, null);
    }

    public static DocumentChange undo(DocumentChange undone, Instant now, ChangeActor actor,
            List<FieldChange> changes) {
        return new DocumentChange(UUID.randomUUID(), undone.documentId, 0, now, actor, DocumentAction.UNDONE,
                changes, undone.id);
    }

    /** A member's own edit that still has something to reverse. */
    public boolean undoable() {
        return actor.kind() == ChangeActor.Kind.MEMBER && action.undoable() && !changes.isEmpty();
    }

    /**
     * The fields where a later change replaced what this change left, so an undo would overwrite it. Labels are
     * compared one by one: a label added later, or another label removed, does not stand in the way; only a label
     * this change added that is gone again, or one it removed that is back.
     */
    public List<String> overwrittenIn(DocumentSnapshot current) {
        List<String> overwritten = new ArrayList<>();
        for (FieldChange change : changes) {
            DocumentField field = DocumentField.ofCode(change.field());
            boolean replaced = field == DocumentField.LABELS
                    ? !labelsStillAsLeft(change, current.labels())
                    : !Objects.equals(current.value(field), change.after());
            if (replaced) {
                overwritten.add(change.field());
            }
        }
        return overwritten;
    }

    /** The labels after undoing this change's label edit on top of {@code current}. */
    public static SortedSet<String> labelsUndone(FieldChange labels, SortedSet<String> current) {
        SortedSet<String> before = DocumentSnapshot.labels(labels.before());
        SortedSet<String> after = DocumentSnapshot.labels(labels.after());
        SortedSet<String> target = new TreeSet<>(current);
        after.stream().filter(id -> !before.contains(id)).forEach(target::remove);
        before.stream().filter(id -> !after.contains(id)).forEach(target::add);
        return target;
    }

    private static boolean labelsStillAsLeft(FieldChange labels, SortedSet<String> current) {
        SortedSet<String> before = DocumentSnapshot.labels(labels.before());
        SortedSet<String> after = DocumentSnapshot.labels(labels.after());
        boolean addedStillThere = after.stream().filter(id -> !before.contains(id)).allMatch(current::contains);
        boolean removedStillGone = before.stream().filter(id -> !after.contains(id)).noneMatch(current::contains);
        return addedStillThere && removedStillGone;
    }
}
