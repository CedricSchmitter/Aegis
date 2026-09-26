package dev.aegis.identity.application;

import dev.aegis.identity.application.api.DesignateAssistants;
import dev.aegis.identity.application.api.MemberCredentials;
import dev.aegis.identity.application.api.MemberAppearance;
import dev.aegis.identity.application.api.MemberCredentialsQuery;
import dev.aegis.identity.application.api.MemberDirectory;
import dev.aegis.identity.application.api.MemberPreferences;
import dev.aegis.identity.application.api.MemberSummary;
import dev.aegis.identity.application.api.ProvisionMembers;
import dev.aegis.identity.domain.Appearance;
import dev.aegis.identity.domain.Member;
import dev.aegis.identity.domain.MemberKind;
import dev.aegis.identity.domain.MemberRepository;
import dev.aegis.identity.domain.Palette;
import dev.aegis.identity.domain.PasswordHasher;
import dev.aegis.identity.domain.Username;
import dev.aegis.shared.Actor;
import dev.aegis.shared.HouseholdAccess;
import dev.aegis.shared.InvalidInputException;
import dev.aegis.shared.MemberId;
import dev.aegis.shared.NotFoundException;
import dev.aegis.shared.ProductLocale;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

/** Coordinates member use cases. Framework-independent; transactions are applied by the composition root. */
public class MemberService implements MemberDirectory, MemberCredentialsQuery, ProvisionMembers, MemberPreferences,
        DesignateAssistants {
    private final MemberRepository members;
    private final PasswordHasher hasher;
    private final HouseholdAccess access;
    private final Clock clock;

    public MemberService(MemberRepository members, PasswordHasher hasher, HouseholdAccess access, Clock clock) {
        this.members = members;
        this.hasher = hasher;
        this.access = access;
        this.clock = clock;
    }

    @Override
    public List<MemberSummary> listMembers(Actor actor) {
        // Open to household processes: the review reconciler notifies every active member (ADR-0009).
        access.requireReader(actor);
        return members.findAll().stream().map(MemberService::summarize).toList();
    }

    @Override
    public Optional<MemberSummary> findMember(MemberId id) {
        return members.findById(id).map(MemberService::summarize);
    }

    @Override
    public Optional<MemberCredentials> findCredentials(String username) {
        Username normalized;
        try {
            normalized = Username.of(username);
        } catch (RuntimeException invalid) {
            return Optional.empty();
        }
        return members.findByUsername(normalized).map(member -> new MemberCredentials(member.id(),
                member.username().value(), member.passwordHash().value(), member.isActive()));
    }

    @Override
    public ProvisioningOutcome ensureMember(ProvisionMemberCommand command) {
        Username username = Username.of(command.username());
        if (members.findByUsername(username).isPresent()) {
            return ProvisioningOutcome.ALREADY_EXISTS;
        }
        MemberKind kind = command.kind() == null ? MemberKind.PERSON : MemberKind.valueOf(command.kind().name());
        Member member = Member.register(username, command.displayName(), command.initialPassword(), hasher,
                command.locale() == null ? ProductLocale.defaultLocale() : command.locale(), kind, clock.instant());
        if (command.appearance() != null) {
            member.changeAppearance(toDomain(command.appearance()));
        }
        members.save(member);
        return ProvisioningOutcome.CREATED;
    }

    @Override
    public List<Designation> designateAssistants(Set<String> usernames, String displayName) {
        List<Designation> outcomes = new ArrayList<>();
        for (String raw : new TreeSet<>(usernames)) {
            Optional<Member> found;
            try {
                found = members.findByUsername(Username.of(raw));
            } catch (RuntimeException invalid) {
                found = Optional.empty();
            }
            if (found.isEmpty()) {
                outcomes.add(new Designation(raw, Outcome.UNKNOWN_USERNAME));
                continue;
            }
            Member member = found.get();
            if (member.designateAssistant(displayName)) {
                members.save(member);
                outcomes.add(new Designation(member.username().value(), Outcome.DESIGNATED));
            } else {
                outcomes.add(new Designation(member.username().value(), Outcome.ALREADY_ASSISTANT));
            }
        }
        return outcomes;
    }

    @Override
    public MemberSummary currentMember(Actor actor) {
        access.requireMember(actor);
        return summarize(load(actor.memberId()));
    }

    @Override
    public MemberSummary changeLocale(Actor actor, ProductLocale locale) {
        access.requireMember(actor);
        Member member = load(actor.memberId());
        member.changeLocale(locale);
        members.save(member);
        return summarize(member);
    }

    @Override
    public MemberSummary changeAppearance(Actor actor, MemberAppearance appearance) {
        access.requireMember(actor);
        Member member = load(actor.memberId());
        member.changeAppearance(toDomain(appearance));
        members.save(member);
        return summarize(member);
    }

    @Override
    public void changePassword(Actor actor, String currentPassword, String newPassword) {
        access.requireMember(actor);
        Member member = load(actor.memberId());
        member.changePassword(currentPassword, newPassword, hasher);
        members.save(member);
    }

    private Member load(MemberId id) {
        return members.findById(id).orElseThrow(() -> new NotFoundException("member", id.toString()));
    }

    static MemberSummary summarize(Member member) {
        return new MemberSummary(member.id(), member.username().value(), member.displayName(), member.locale(),
                member.isActive(), dev.aegis.identity.application.api.MemberKind.valueOf(member.kind().name()),
                new MemberAppearance(MemberAppearance.Palette.valueOf(member.appearance().palette().name()),
                        member.appearance().effects()));
    }

    private static Appearance toDomain(MemberAppearance appearance) {
        if (appearance == null) {
            throw new InvalidInputException("appearance.invalid", "Palette and effects are required");
        }
        return new Appearance(Palette.valueOf(appearance.palette().name()), appearance.effects());
    }
}
