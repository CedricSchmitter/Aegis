package dev.aegis.portability.application;

import static org.assertj.core.api.Assertions.assertThat;

import dev.aegis.identity.application.api.MemberAppearance;
import dev.aegis.identity.application.api.MemberAppearance.Palette;
import dev.aegis.portability.application.ExportFormat.MemberEntry;
import org.junit.jupiter.api.Test;

/** A member created by a restore gets the exported palette and effects; older files give the default (schema 10). */
class RestoredAppearanceTest {

    @Test
    void schemaTenCarriesPaletteAndEffects() {
        MemberEntry entry = new MemberEntry("id", "lisa", "Lisa", "DE", true, "PERSON", "NOMAD", false);

        assertThat(RestoreService.appearance(entry)).isEqualTo(new MemberAppearance(Palette.NOMAD, false));
    }

    @Test
    void filesBeforeSchemaTenRestoreStreetkidWithEffectsOn() {
        MemberEntry entry = new MemberEntry("id", "lisa", "Lisa", "DE", true, "PERSON", null, null);

        assertThat(RestoreService.appearance(entry)).isEqualTo(MemberAppearance.DEFAULT);
    }

    @Test
    void anUnknownPaletteIsALookAndFallsBackInsteadOfFailingTheRestore() {
        MemberEntry entry = new MemberEntry("id", "lisa", "Lisa", "DE", true, "PERSON", "NEON", false);

        assertThat(RestoreService.appearance(entry)).isEqualTo(new MemberAppearance(Palette.STREETKID, false));
    }
}
