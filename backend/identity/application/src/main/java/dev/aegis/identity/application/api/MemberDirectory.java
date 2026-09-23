package dev.aegis.identity.application.api;

import dev.aegis.shared.Actor;
import dev.aegis.shared.MemberId;
import java.util.List;
import java.util.Optional;

/** Public contract: who belongs to the household. Used for task assignment, subjects and exports. */
public interface MemberDirectory {
    /** Every member, active or not; also open to household processes that notify members (ADR-0009). */
    List<MemberSummary> listMembers(Actor actor);

    /** Lookup without actor for internal collaboration between contexts (for example notifications or exports). */
    Optional<MemberSummary> findMember(MemberId id);
}
