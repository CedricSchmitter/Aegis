package dev.aegis.documents.application;

import dev.aegis.documents.application.api.*;
import dev.aegis.documents.application.ports.PassageIndex;
import dev.aegis.shared.*;
import java.time.Clock;

/** All public entry points require a household member before accessing derived data. */
public final class PassageService implements PassageQueries, PassageCommands {
    private final PassageIndex index;
    private final HouseholdAccess access;
    private final Clock clock;
    public PassageService(PassageIndex index, HouseholdAccess access, Clock clock) {
        this.index = index; this.access = access; this.clock = clock;
    }
    @Override public Page search(Actor actor, String query, String cursor, int limit) {
        access.requireMember(actor);
        if (query == null || query.isBlank() || query.length() > 200 || limit < 1 || limit > 50) {
            throw new InvalidInputException("passages.invalidSearch", "Use a query of 1-200 characters and a limit of 1-50");
        }
        return index.search(query, cursor, limit, clock.instant());
    }
    @Override public Passage read(Actor actor, DocumentId id, String revision, int ordinal) {
        access.requireMember(actor);
        return index.read(id, revision, ordinal, clock.instant())
                .orElseThrow(() -> new NotFoundException("passage", id + "/" + revision + "/" + ordinal));
    }
    @Override public Coverage coverage(Actor actor) { access.requireMember(actor); return index.coverage(clock.instant()); }
    @Override public Status status(Actor actor, DocumentId id) { access.requireMember(actor); return index.status(id, clock.instant()); }
    @Override public void retry(Actor actor, DocumentId id) { access.requireMember(actor); index.invalidate(id, clock.instant()); }
    @Override public void rebuild(Actor actor) { access.requireMember(actor); index.rebuild(clock.instant()); }
}
