package dev.aegis.documents.domain.naming;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.aegis.documents.domain.naming.FriendlyFilename.Components;
import dev.aegis.shared.DocumentId;
import dev.aegis.shared.InvalidInputException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class FriendlyFilenameTest {
    private static final DocumentId ID = DocumentId.of("a1b2c3d4-0000-4000-8000-000000000001");
    private static final DocumentId TWIN = DocumentId.of("e5f6a7b8-0000-4000-8000-000000000002");
    private static final String PDF = "application/pdf";

    @Test
    void telekomInvoiceGetsTheDocumentedName() {
        String name = FriendlyFilename.render(ID, new Components(LocalDate.of(2026, 9, 17), "Telekom", "Rechnung",
                "Internet"), "Scan 0001", PDF);

        assertThat(name).isEqualTo("2026-09-17_Telekom_Rechnung_Internet_a1b2c3d4.pdf");
    }

    @Test
    void unknownComponentsAreOmittedAndNeverGuessed() {
        assertThat(FriendlyFilename.render(ID, new Components(null, "Telekom", "Rechnung", null), "x", PDF))
                .isEqualTo("Telekom_Rechnung_a1b2c3d4.pdf");
        assertThat(FriendlyFilename.render(ID, new Components(null, null, null, null), "Scan vom Drucker", PDF))
                .isEqualTo("Scan-vom-Drucker_a1b2c3d4.pdf");
        assertThat(FriendlyFilename.render(ID, new Components(null, null, null, null), "???", PDF))
                .isEqualTo("document_a1b2c3d4.pdf");
    }

    @Test
    void identicalLookingDocumentsNeverShareAName() {
        Components same = new Components(LocalDate.of(2026, 9, 17), "Telekom", "Rechnung", "Internet");

        assertThat(FriendlyFilename.render(ID, same, "x", PDF)).isNotEqualTo(FriendlyFilename.render(TWIN, same,
                "x", PDF));
        assertThat(FriendlyFilename.withFullId("Rechnung_a1b2c3d4.pdf", ID))
                .isEqualTo("Rechnung_a1b2c3d4000040008000000000000001.pdf");
    }

    @Test
    void pathHeaderAndControlCharactersNeverSurvive() {
        String name = FriendlyFilename.render(ID, new Components(null, "../../etc/passwd", "Rech\r\nnung\"; x=1",
                "a\\b:c*d?e|f<g>h"), "x", PDF);

        assertThat(name).doesNotContain("/", "\\", "..", "\r", "\n", "\"", ":", "*", "?", "|", "<", ">")
                .endsWith("_a1b2c3d4.pdf");
        assertThat(FriendlyFilename.asciiFallback("Kündigung \"x\";%.pdf")).isEqualTo("Kuendigung _x___.pdf");
    }

    @Test
    void unicodeIsNormalizedAndBidiOverridesAreRemoved() {
        String name = FriendlyFilename.render(ID, new Components(null, "Ｔｅｌｅｋｏｍ", "Rechnung‮fdp.exe",
                "Straße"), "x", PDF);

        assertThat(name).isEqualTo("Telekom_Rechnung-fdp.exe_Straße_a1b2c3d4.pdf");
    }

    @Test
    void accountNumbersAndIbansAreDropped() {
        String name = FriendlyFilename.render(ID, new Components(null, "Sparkasse", "Kontoauszug",
                "DE89 3704 0044 0532 0130 00 Kunde 123456789 Juli 2026"), "x", PDF);

        assertThat(name).isEqualTo("Sparkasse_Kontoauszug_Kunde-Juli-2026_a1b2c3d4.pdf");
    }

    @Test
    void longNamesAreBoundedInBytesAndTrailingDotsRemoved() {
        String name = FriendlyFilename.render(ID, new Components(null, "Ä".repeat(200) + ". ", "Ü".repeat(200),
                "ö".repeat(200)), "x", PDF);

        assertThat(name.getBytes(StandardCharsets.UTF_8).length).isLessThanOrEqualTo(
                FriendlyFilename.MAX_DESCRIPTIVE_BYTES + "_a1b2c3d4.pdf".length());
        assertThat(name).doesNotContain("._").endsWith("_a1b2c3d4.pdf");
    }

    @Test
    void reservedWindowsNamesAreDefused() {
        assertThat(FriendlyFilename.render(ID, new Components(null, null, null, null), "CON", PDF))
                .isEqualTo("CON_a1b2c3d4.pdf");
        assertThat(FriendlyFilename.renderOverride(ID, "nul", PDF)).isEqualTo("nul_a1b2c3d4.pdf");
        assertThat(FriendlyFilename.renderOverride(ID, "Aux.pdf", PDF)).startsWith("Aux_");
    }

    @Test
    void memberOverrideKeepsTheStableIdExactlyOnceAndTheVerifiedExtension() {
        assertThat(FriendlyFilename.renderOverride(ID, "Mein Vertrag_a1b2c3d4.PDF", PDF))
                .isEqualTo("Mein-Vertrag_a1b2c3d4.pdf");
        assertThat(FriendlyFilename.renderOverride(ID, "evil.exe", PDF)).isEqualTo("evil.exe_a1b2c3d4.pdf");
        assertThat(FriendlyFilename.renderOverride(ID, "Rechnung 12345678", PDF))
                .isEqualTo("Rechnung-12345678_a1b2c3d4.pdf");
        assertThat(FriendlyFilename.render(ID, new Components(null, null, null, "x"), "x", "text/html"))
                .endsWith(".bin");
        assertThatThrownBy(() -> FriendlyFilename.overrideStem(ID, " /\\:*?\"<>| ")).isInstanceOf(
                InvalidInputException.class).hasFieldOrPropertyWithValue("code", "document.filename.invalid");
    }

    @Test
    void renderingIsDeterministic() {
        Components components = new Components(LocalDate.of(2026, 1, 2), "Allianz", "Versicherungspolice",
                "Hausrat");

        assertThat(FriendlyFilename.render(ID, components, "a", PDF)).isEqualTo(FriendlyFilename.render(ID,
                components, "b", PDF));
    }
}
