package dev.aegis.identity.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.aegis.shared.InvalidInputException;
import dev.aegis.shared.ProductLocale;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class MemberTest {
    private static final PasswordHasher REVERSIBLE = new PasswordHasher() {
        @Override
        public PasswordHash hash(String plainPassword) {
            return new PasswordHash("h:" + plainPassword);
        }

        @Override
        public boolean matches(String plainPassword, PasswordHash hash) {
            return hash.value().equals("h:" + plainPassword);
        }
    };

    @Test
    void registersWithHashedPasswordAndDefaultsToActive() {
        Member member = Member.register(Username.of(" Anna "), "Anna", "correct-horse-1", REVERSIBLE, ProductLocale.DE,
                Instant.EPOCH);

        assertThat(member.username().value()).isEqualTo("anna");
        assertThat(member.isActive()).isTrue();
        assertThat(member.passwordHash().value()).doesNotContain("correct-horse-1".substring(0, 3) + "-horse");
        assertThat(member.passwordMatches("correct-horse-1", REVERSIBLE)).isTrue();
        assertThat(member.passwordMatches("wrong", REVERSIBLE)).isFalse();
    }

    @Test
    void rejectsShortPasswordsAndInvalidUsernames() {
        assertThatThrownBy(() -> Member.register(Username.of("anna"), "Anna", "short", REVERSIBLE, ProductLocale.DE,
                Instant.EPOCH)).isInstanceOf(InvalidInputException.class)
                .hasFieldOrPropertyWithValue("code", "password.tooShort");
        assertThatThrownBy(() -> Username.of("A B")).isInstanceOf(InvalidInputException.class);
    }

    @Test
    void passwordChangeRequiresCurrentPassword() {
        Member member = Member.register(Username.of("ben"), "Ben", "initial-secret", REVERSIBLE, ProductLocale.EN,
                Instant.EPOCH);

        assertThatThrownBy(() -> member.changePassword("nope", "another-secret", REVERSIBLE))
                .isInstanceOf(InvalidInputException.class);
        member.changePassword("initial-secret", "another-secret", REVERSIBLE);

        assertThat(member.passwordMatches("another-secret", REVERSIBLE)).isTrue();
    }

    @Test
    void deactivatedMemberCannotAuthenticate() {
        Member member = Member.register(Username.of("ben"), "Ben", "initial-secret", REVERSIBLE, ProductLocale.EN,
                Instant.EPOCH);
        member.deactivate();

        assertThat(member.passwordMatches("initial-secret", REVERSIBLE)).isFalse();
    }

    @Test
    void registeredMembersArePersonsUnlessRegisteredAsTheAssistant() {
        Member person = Member.register(Username.of("lisa"), "Lisa", "initial-secret", REVERSIBLE, ProductLocale.DE,
                Instant.EPOCH);
        Member assistant = Member.register(Username.of("helper"), "Aegis", "initial-secret", REVERSIBLE,
                ProductLocale.DE, MemberKind.ASSISTANT, Instant.EPOCH);

        assertThat(person.kind()).isEqualTo(MemberKind.PERSON);
        assertThat(person.isPerson()).isTrue();
        assertThat(assistant.isPerson()).isFalse();
    }

    @Test
    void designatingTheAssistantRenamesItOnceAndKeepsItsLogin() {
        Member member = Member.register(Username.of("aegis-dev"), "Aegis Dev", "initial-secret", REVERSIBLE,
                ProductLocale.DE, Instant.EPOCH);

        assertThat(member.designateAssistant(" Aegis ")).isTrue();
        assertThat(member.kind()).isEqualTo(MemberKind.ASSISTANT);
        assertThat(member.displayName()).isEqualTo("Aegis");
        assertThat(member.username().value()).isEqualTo("aegis-dev");
        assertThat(member.passwordMatches("initial-secret", REVERSIBLE)).isTrue();

        member.rename("Aegis Assistent");
        assertThat(member.designateAssistant("Aegis")).isFalse();
        assertThat(member.displayName()).isEqualTo("Aegis Assistent");
    }

    @Test
    void designationNeedsAValidName() {
        Member member = Member.register(Username.of("aegis-dev"), "Aegis Dev", "initial-secret", REVERSIBLE,
                ProductLocale.DE, Instant.EPOCH);

        assertThatThrownBy(() -> member.designateAssistant(" ")).isInstanceOf(InvalidInputException.class);
        assertThat(member.kind()).isEqualTo(MemberKind.PERSON);
        assertThat(member.displayName()).isEqualTo("Aegis Dev");
    }
}
