package dev.aegis.documents.adapters.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import java.util.Objects;
import java.util.UUID;

/** Row of document_subjects. */
@Embeddable
public class SubjectEmbeddable {
    @Column(name = "kind", nullable = false, length = 20)
    private String kind;

    @Column(name = "member_id")
    private UUID memberId;

    protected SubjectEmbeddable() {
    }

    SubjectEmbeddable(String kind, UUID memberId) {
        this.kind = kind;
        this.memberId = memberId;
    }

    String getKind() {
        return kind;
    }

    UUID getMemberId() {
        return memberId;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof SubjectEmbeddable that && Objects.equals(kind, that.kind)
                && Objects.equals(memberId, that.memberId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(kind, memberId);
    }
}
