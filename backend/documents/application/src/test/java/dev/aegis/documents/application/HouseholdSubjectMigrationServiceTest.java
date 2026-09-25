package dev.aegis.documents.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.aegis.documents.application.api.MigrateHouseholdSubjects.Result;
import dev.aegis.documents.application.ports.LegacyHouseholdSubjects;
import dev.aegis.shared.MemberId;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class HouseholdSubjectMigrationServiceTest {
    private final MemberId lisa = MemberId.random();
    private final MemberId cedric = MemberId.random();

    @Test
    void replacesTheStoredValueWithTheCurrentPersons() {
        FakeLegacy legacy = new FakeLegacy(true);

        Result result = new HouseholdSubjectMigrationService(legacy, new TestDoubles.Persons(lisa, cedric))
                .migrateHouseholdSubjects();

        assertThat(legacy.calls).containsExactly(List.of(lisa, cedric));
        assertThat(result).isEqualTo(new Result(true, 2, 3, 1, 2));
    }

    @Test
    void doesNothingOnceNoValueIsLeft() {
        FakeLegacy legacy = new FakeLegacy(false);

        Result result = new HouseholdSubjectMigrationService(legacy, new TestDoubles.Persons(lisa))
                .migrateHouseholdSubjects();

        assertThat(result).isEqualTo(Result.NOTHING_TO_MIGRATE);
        assertThat(legacy.calls).isEmpty();
    }

    @Test
    void keepsTheValueWhenThereIsNoPersonToReplaceItWith() {
        FakeLegacy legacy = new FakeLegacy(true);

        assertThatThrownBy(() -> new HouseholdSubjectMigrationService(legacy, new TestDoubles.Persons())
                .migrateHouseholdSubjects()).isInstanceOf(IllegalStateException.class);
        assertThat(legacy.calls).isEmpty();
    }

    private static final class FakeLegacy implements LegacyHouseholdSubjects {
        private final boolean present;
        final List<List<MemberId>> calls = new ArrayList<>();

        FakeLegacy(boolean present) {
            this.present = present;
        }

        @Override
        public boolean present() {
            return present;
        }

        @Override
        public Replaced replaceWith(List<MemberId> persons) {
            calls.add(List.copyOf(persons));
            return new Replaced(3, 1, 2);
        }
    }
}
