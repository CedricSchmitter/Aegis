package dev.aegis.identity.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.aegis.identity.application.api.ProvisionMembers.ProvisionMemberCommand;
import dev.aegis.identity.domain.PasswordHash;
import dev.aegis.identity.domain.PasswordHasher;
import dev.aegis.shared.Actor;
import dev.aegis.shared.MemberId;
import dev.aegis.shared.NotAuthorizedException;
import dev.aegis.shared.ProductLocale;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Household processes (ADR-0009) read what is opened to them and are never treated as members. */
class HouseholdAccessPolicyTest {
    private static final Actor PROCESS = Actor.householdProcess("review-reconciler");

    private InMemoryMemberRepository repository;
    private HouseholdAccessPolicy policy;
    private MemberService members;
    private MemberId anna;

    @BeforeEach
    void setUp() {
        repository = new InMemoryMemberRepository();
        policy = new HouseholdAccessPolicy(repository);
        members = new MemberService(repository, new PasswordHasher() {
            @Override
            public PasswordHash hash(String plainPassword) {
                return new PasswordHash("h:" + plainPassword);
            }

            @Override
            public boolean matches(String plainPassword, PasswordHash hash) {
                return hash.value().equals("h:" + plainPassword);
            }
        }, policy, Clock.fixed(Instant.parse("2026-09-23T10:00:00Z"), ZoneOffset.UTC));
        members.ensureMember(new ProvisionMemberCommand("anna", "Anna", "initial-password", ProductLocale.DE));
        anna = members.findCredentials("anna").orElseThrow().id();
    }

    @Test
    void aHouseholdProcessMayReadButIsNeverAMember() {
        assertThatCode(() -> policy.requireReader(PROCESS)).doesNotThrowAnyException();
        assertThatThrownBy(() -> policy.requireMember(PROCESS)).isInstanceOf(NotAuthorizedException.class)
                .hasMessageContaining("review-reconciler");
        assertThatThrownBy(PROCESS::memberId).isInstanceOf(NotAuthorizedException.class);
    }

    @Test
    void theMemberDirectoryIsOpenToHouseholdProcessesButMemberCommandsAreNot() {
        assertThat(members.listMembers(PROCESS)).extracting("username").containsExactly("anna");
        assertThatThrownBy(() -> members.changeLocale(PROCESS, ProductLocale.EN))
                .isInstanceOf(NotAuthorizedException.class);
    }

    @Test
    void readingStillRequiresAnActiveMemberForEveryoneElse() {
        assertThatCode(() -> policy.requireReader(new Actor(anna))).doesNotThrowAnyException();
        assertThatThrownBy(() -> policy.requireReader(new Actor(MemberId.random())))
                .isInstanceOf(NotAuthorizedException.class);
        assertThatThrownBy(() -> policy.requireReader(null)).isInstanceOf(NotAuthorizedException.class);
    }

    @Test
    void anActorIsEitherAMemberOrANamedHouseholdProcess() {
        assertThatThrownBy(() -> new Actor(null, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Actor(anna, "review-reconciler")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Actor.householdProcess(" ")).isInstanceOf(IllegalArgumentException.class);
        assertThat(new Actor(anna).isHouseholdProcess()).isFalse();
        assertThat(PROCESS).isEqualTo(Actor.householdProcess("review-reconciler"));
    }
}
