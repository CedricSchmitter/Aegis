package dev.aegis.identity.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import dev.aegis.identity.application.api.DesignateAssistants.Designation;
import dev.aegis.identity.application.api.DesignateAssistants.Outcome;
import dev.aegis.identity.application.api.MemberKind;
import dev.aegis.identity.application.api.MemberSummary;
import dev.aegis.identity.application.api.ProvisionMembers.ProvisionMemberCommand;
import dev.aegis.identity.application.api.ProvisionMembers.ProvisioningOutcome;
import dev.aegis.identity.domain.PasswordHash;
import dev.aegis.identity.domain.PasswordHasher;
import dev.aegis.shared.Actor;
import dev.aegis.shared.MemberId;
import dev.aegis.shared.NotAuthorizedException;
import dev.aegis.shared.ProductLocale;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class MemberServiceTest {
    private static final PasswordHasher HASHER = new PasswordHasher() {
        @Override
        public PasswordHash hash(String plainPassword) {
            return new PasswordHash("h:" + plainPassword);
        }

        @Override
        public boolean matches(String plainPassword, PasswordHash hash) {
            return hash.value().equals("h:" + plainPassword);
        }
    };

    private InMemoryMemberRepository repository;
    private MemberService service;

    @BeforeEach
    void setUp() {
        repository = new InMemoryMemberRepository();
        service = new MemberService(repository, HASHER, new HouseholdAccessPolicy(repository),
                Clock.fixed(Instant.parse("2026-09-13T10:00:00Z"), ZoneOffset.UTC));
    }

    @Test
    void provisioningIsIdempotentPerUsername() {
        var command = new ProvisionMemberCommand("Anna", "Anna", "initial-password", ProductLocale.DE);

        assertThat(service.ensureMember(command)).isEqualTo(ProvisioningOutcome.CREATED);
        assertThat(service.ensureMember(command)).isEqualTo(ProvisioningOutcome.ALREADY_EXISTS);
        assertThat(repository.findAll()).hasSize(1);
    }

    @Test
    void bothMembersSeeEachOtherInTheDirectory() {
        service.ensureMember(new ProvisionMemberCommand("anna", "Anna", "initial-password", ProductLocale.DE));
        service.ensureMember(new ProvisionMemberCommand("ben", "Ben", "initial-password", ProductLocale.EN));
        MemberId anna = service.findCredentials("anna").orElseThrow().id();

        assertThat(service.listMembers(new Actor(anna))).extracting("username").containsExactly("anna", "ben");
    }

    @Test
    void unknownActorIsRejected() {
        assertThatThrownBy(() -> service.listMembers(new Actor(MemberId.random())))
                .isInstanceOf(NotAuthorizedException.class);
    }

    @Test
    void credentialsLookupNormalizesAndRejectsInvalidNames() {
        service.ensureMember(new ProvisionMemberCommand("anna", "Anna", "initial-password", ProductLocale.DE));

        assertThat(service.findCredentials(" ANNA ")).isPresent();
        assertThat(service.findCredentials("no spaces allowed")).isEmpty();
    }

    @Test
    void localeChangeDoesNotTouchOtherState() {
        service.ensureMember(new ProvisionMemberCommand("anna", "Anna", "initial-password", ProductLocale.DE));
        MemberId anna = service.findCredentials("anna").orElseThrow().id();

        var updated = service.changeLocale(new Actor(anna), ProductLocale.EN);

        assertThat(updated.locale()).isEqualTo(ProductLocale.EN);
        assertThat(updated.displayName()).isEqualTo("Anna");
        assertThat(service.findCredentials("anna").orElseThrow().passwordHash()).isEqualTo("h:initial-password");
    }

    @Test
    void designationMarksOnlyTheConfiguredAccountAndIsIdempotent() {
        service.ensureMember(new ProvisionMemberCommand("lisa", "Lisa", "initial-password", ProductLocale.DE));
        service.ensureMember(new ProvisionMemberCommand("aegis-dev", "Aegis Dev", "initial-password",
                ProductLocale.DE));
        Actor lisa = new Actor(service.findCredentials("lisa").orElseThrow().id());

        assertThat(service.designateAssistants(Set.of("aegis-dev", "nobody"), "Aegis")).containsExactly(
                new Designation("aegis-dev", Outcome.DESIGNATED), new Designation("nobody", Outcome.UNKNOWN_USERNAME));
        assertThat(service.designateAssistants(Set.of("aegis-dev"), "Aegis"))
                .containsExactly(new Designation("aegis-dev", Outcome.ALREADY_ASSISTANT));

        assertThat(service.listMembers(lisa)).extracting(MemberSummary::username, MemberSummary::displayName,
                MemberSummary::kind).containsExactly(
                        tuple("lisa", "Lisa", MemberKind.PERSON),
                        tuple("aegis-dev", "Aegis", MemberKind.ASSISTANT));
        assertThat(service.findCredentials("aegis-dev")).isPresent();
    }

    @Test
    void provisioningKeepsAnExportedAssistantKind() {
        service.ensureMember(new ProvisionMemberCommand("helper", "Aegis", "initial-password", ProductLocale.DE,
                MemberKind.ASSISTANT));
        service.ensureMember(new ProvisionMemberCommand("lisa", "Lisa", "initial-password", ProductLocale.DE));
        Actor lisa = new Actor(service.findCredentials("lisa").orElseThrow().id());

        assertThat(service.listMembers(lisa)).filteredOn(MemberSummary::isPerson).extracting(MemberSummary::username)
                .containsExactly("lisa");
    }
}
