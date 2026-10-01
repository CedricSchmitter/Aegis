package dev.aegis.contracts.application;

import dev.aegis.documents.application.api.DocumentReference;
import dev.aegis.documents.application.api.DocumentReferenceQuery;
import dev.aegis.shared.Actor;
import dev.aegis.shared.DocumentId;
import dev.aegis.shared.HouseholdAccess;
import dev.aegis.shared.MemberId;
import dev.aegis.shared.NotAuthorizedException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Small hand-written doubles: the household, an archive of known documents and a fixed household clock. */
final class ContractTestSupport {
    static final ZoneId BERLIN = ZoneId.of("Europe/Berlin");
    static final Actor LISA = new Actor(new MemberId(UUID.randomUUID()));
    static final Actor CEDRIC = new Actor(new MemberId(UUID.randomUUID()));
    static final Actor STRANGER = new Actor(new MemberId(UUID.randomUUID()));

    private ContractTestSupport() {
    }

    static Clock clockAt(String instant) {
        return Clock.fixed(Instant.parse(instant), BERLIN);
    }

    /** Only Lisa and Cedric are household members; everybody else is rejected in the application service. */
    static HouseholdAccess household() {
        return actor -> {
            if (!actor.memberId().equals(LISA.memberId()) && !actor.memberId().equals(CEDRIC.memberId())) {
                throw new NotAuthorizedException("not a household member");
            }
        };
    }

    /** The documents context as the contracts context sees it: a lookup of known document references. */
    static final class KnownDocuments implements DocumentReferenceQuery {
        private final Map<DocumentId, String> titles = new LinkedHashMap<>();
        private final java.util.Set<DocumentId> trashed = new java.util.HashSet<>();

        DocumentId add(String title) {
            DocumentId id = DocumentId.random();
            titles.put(id, title);
            return id;
        }

        /** A member deleted the document permanently; the documents context no longer knows it. */
        void purge(DocumentId id) {
            titles.remove(id);
            trashed.remove(id);
        }

        /** The document is moved to the trash in the documents context. */
        void trash(DocumentId id) {
            trashed.add(id);
        }

        @Override
        public Optional<DocumentReference> findReference(DocumentId id) {
            return Optional.ofNullable(titles.get(id))
                    .map(title -> new DocumentReference(id, title, trashed.contains(id)));
        }
    }
}
