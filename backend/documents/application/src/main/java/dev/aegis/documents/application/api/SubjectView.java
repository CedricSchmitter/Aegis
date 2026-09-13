package dev.aegis.documents.application.api;

import dev.aegis.shared.MemberId;

/** Boundary representation of a subject: kind is MEMBER, HOUSE or HOUSEHOLD; memberId only for MEMBER. */
public record SubjectView(String kind, MemberId memberId) {
}
