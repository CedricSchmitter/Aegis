package dev.aegis.documents.domain.history;

import dev.aegis.shared.FieldChange;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;

/**
 * The recorded fields of a document at one moment. They belong to three aggregates (the document, its classification
 * and its analysis); the snapshot reads them together so one command yields one entry.
 *
 * @param folder the folder id, {@code null} for Unsorted
 * @param filename the member's friendly filename stem, {@code null} for the generated name
 * @param labels ids of the document's own effective labels
 */
public record DocumentSnapshot(String title, String filename, String folder, SortedSet<String> labels) {

    public DocumentSnapshot {
        labels = Collections.unmodifiableSortedSet(new TreeSet<>(labels));
    }

    public static DocumentSnapshot of(String title, String filename, String folder, Set<String> labels) {
        return new DocumentSnapshot(title, filename, folder, new TreeSet<>(labels));
    }

    /** The fields that differ from {@code before} to this snapshot, in field order. */
    public List<FieldChange> changesSince(DocumentSnapshot before) {
        List<FieldChange> changes = new ArrayList<>();
        for (DocumentField field : DocumentField.values()) {
            String old = before.value(field);
            String now = value(field);
            if (!Objects.equals(old, now)) {
                changes.add(new FieldChange(field.code(), old, now));
            }
        }
        return changes;
    }

    public String value(DocumentField field) {
        return switch (field) {
            case TITLE -> title;
            case FILENAME -> filename;
            case FOLDER -> folder;
            case LABELS -> labels.isEmpty() ? null : String.join(",", labels);
        };
    }

    /** Label ids from a stored {@code labels} value. */
    public static SortedSet<String> labels(String value) {
        SortedSet<String> ids = new TreeSet<>();
        if (value != null && !value.isEmpty()) {
            ids.addAll(List.of(value.split(",")));
        }
        return ids;
    }
}
