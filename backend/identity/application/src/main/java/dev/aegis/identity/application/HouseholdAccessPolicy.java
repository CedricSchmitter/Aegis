package dev.aegis.identity.application;

import dev.aegis.identity.domain.MemberRepository;
import dev.aegis.shared.Actor;
import dev.aegis.shared.HouseholdAccess;
import dev.aegis.shared.NotAuthorizedException;

/**
 * Single shared household: every active member may read and change household records. Subject metadata never
 * restricts access. Unknown or deactivated actors are rejected for every entry point.
 */
public class HouseholdAccessPolicy implements HouseholdAccess {
    private final MemberRepository members;

    public HouseholdAccessPolicy(MemberRepository members) {
        this.members = members;
    }

    @Override
    public void requireMember(Actor actor) {
        if (actor == null) {
            throw new NotAuthorizedException("authentication required");
        }
        boolean active = members.findById(actor.memberId()).map(member -> member.isActive()).orElse(false);
        if (!active) {
            throw new NotAuthorizedException("member " + actor.memberId() + " is not an active household member");
        }
    }
}
