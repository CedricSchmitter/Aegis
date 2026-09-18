package dev.aegis.documents.domain.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.aegis.shared.InvalidInputException;
import dev.aegis.shared.MemberId;
import dev.aegis.shared.NotFoundException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class LabelVocabularyTest {
    private static final Instant NOW = Instant.parse("2026-09-17T10:00:00Z");
    private static final MemberId CEDRIC = MemberId.random();

    private static Label organization(String name) {
        return Label.create(LabelId.random(), Facet.ORGANIZATION, name, CEDRIC, NOW);
    }

    private static Label builtinInvoice() {
        return Label.rehydrate(LabelId.random(), Facet.DOCUMENT_TYPE, "invoice", null, List.of("Rechnung", "Invoice"),
                LabelStatus.ACTIVE, null, null, NOW, NOW, 0);
    }

    @Test
    void namesKeepTheirSpellingWhileMatchKeysFoldCaseWidthAndSpacing() {
        Label telekom = organization("  Deutsche   Telekom ");

        assertThat(telekom.name()).contains("Deutsche Telekom");
        assertThat(telekom.matchKeys()).containsExactly("deutsche telekom");
        assertThat(CatalogNames.matchKey("ＴＥＬＥＫＯＭ")).isEqualTo("telekom");
        assertThat(CatalogNames.matchKey("Müller")).isNotEqualTo(CatalogNames.matchKey("Muller"));
        assertThatThrownBy(() -> organization("Telekom")).hasFieldOrPropertyWithValue("code",
                "label.name.invalid");
    }

    @Test
    void equalNamesOrAliasesInOneFacetAreRejectedButSimilarNamesAreNot() {
        Label telekom = organization("Telekom");
        telekom.replaceAliases(List.of("Deutsche Telekom", "telekom", "DEUTSCHE TELEKOM"), NOW);
        LabelVocabulary vocabulary = new LabelVocabulary(List.of(telekom));

        assertThat(telekom.aliases()).containsExactly("Deutsche Telekom");
        assertThatThrownBy(() -> vocabulary.requireUniqueKeys(organization("deutsche telekom")))
                .isInstanceOfSatisfying(InvalidInputException.class, e -> {
                    assertThat(e.code()).isEqualTo("label.exists");
                    assertThat(e.details()).containsEntry("existingId", telekom.id().toString());
                });
        vocabulary.requireUniqueKeys(organization("Telekom Deutschland GmbH"));
        vocabulary.requireUniqueKeys(Label.create(LabelId.random(), Facet.TOPIC, "Telekom", CEDRIC, NOW));
        assertThat(vocabulary.findByName(Facet.ORGANIZATION, "DEUTSCHE  telekom")).contains(telekom);
        assertThat(vocabulary.findByName(Facet.TOPIC, "Telekom")).isEmpty();
    }

    @Test
    void tagsCannotShadowActiveCanonicalValuesAndViceVersa() {
        Label telekom = organization("Telekom");
        Label urgent = Label.create(LabelId.random(), Facet.TAG, "Dringend", CEDRIC, NOW);
        LabelVocabulary vocabulary = new LabelVocabulary(List.of(telekom, urgent, builtinInvoice()));

        assertThatThrownBy(() -> vocabulary.requireUniqueKeys(Label.create(LabelId.random(), Facet.TAG, "telekom",
                CEDRIC, NOW))).hasFieldOrPropertyWithValue("code", "label.existsInOtherFacet");
        assertThatThrownBy(() -> vocabulary.requireUniqueKeys(Label.create(LabelId.random(), Facet.TAG, "Invoice",
                CEDRIC, NOW))).hasFieldOrPropertyWithValue("code", "label.existsInOtherFacet");
        assertThatThrownBy(() -> vocabulary.requireUniqueKeys(Label.create(LabelId.random(), Facet.TOPIC, "dringend",
                CEDRIC, NOW))).hasFieldOrPropertyWithValue("code", "label.existsInOtherFacet");

        telekom.retire(NOW);
        vocabulary.requireUniqueKeys(Label.create(LabelId.random(), Facet.TAG, "Telekom", CEDRIC, NOW));
    }

    @Test
    void mergingMovesNamesIntoTheTargetAndLeavesARedirect() {
        Label telekom = organization("Telekom");
        Label longName = organization("Telekom Deutschland GmbH");
        longName.replaceAliases(List.of("TDG"), NOW);
        LabelVocabulary vocabulary = new LabelVocabulary(List.of(telekom, longName));

        longName.mergeInto(telekom, NOW);

        assertThat(telekom.aliases()).containsExactly("Telekom Deutschland GmbH", "TDG");
        assertThat(longName.status()).isEqualTo(LabelStatus.MERGED);
        assertThat(longName.matchKeys()).isEmpty();
        assertThat(vocabulary.resolve(longName.id())).isSameAs(telekom);
        assertThat(vocabulary.findByName(Facet.ORGANIZATION, "tdg")).contains(telekom);
        assertThatThrownBy(() -> longName.rename("Again", NOW)).hasFieldOrPropertyWithValue("code", "label.merged");
        assertThatThrownBy(() -> vocabulary.resolve(LabelId.random())).isInstanceOf(NotFoundException.class);
    }

    @Test
    void mergesRequireTheSameFacetADifferentActiveTarget() {
        Label telekom = organization("Telekom");
        Label vodafone = organization("Vodafone");
        Label topic = Label.create(LabelId.random(), Facet.TOPIC, "Internet", CEDRIC, NOW);

        assertThatThrownBy(() -> telekom.mergeInto(telekom, NOW)).hasFieldOrPropertyWithValue("code",
                "label.merge.self");
        assertThatThrownBy(() -> telekom.mergeInto(topic, NOW)).hasFieldOrPropertyWithValue("code",
                "label.merge.facet");
        vodafone.retire(NOW);
        assertThatThrownBy(() -> telekom.mergeInto(vodafone, NOW)).hasFieldOrPropertyWithValue("code",
                "label.merge.targetInactive");
        vodafone.reactivate(NOW);
        telekom.mergeInto(vodafone, NOW);
        assertThat(vodafone.aliases()).containsExactly("Telekom");
    }

    @Test
    void builtinLabelsCanBeRenamedWithoutLosingTheirKey() {
        Label invoice = builtinInvoice();

        assertThat(invoice.name()).isEmpty();
        assertThat(invoice.matchKeys()).containsExactly("rechnung", "invoice");
        invoice.rename("Rechnung", NOW);

        assertThat(invoice.builtinKey()).contains("invoice");
        assertThat(invoice.name()).contains("Rechnung");
        assertThat(invoice.aliases()).containsExactly("Invoice");
        assertThatThrownBy(() -> Label.rehydrate(LabelId.random(), Facet.TOPIC, null, null, List.of(),
                LabelStatus.ACTIVE, null, null, NOW, NOW, 0)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void onlyTagsConvertAndOnlyIntoMultiValuedCanonicalFacets() {
        Label tag = Label.create(LabelId.random(), Facet.TAG, "Glasfaser", CEDRIC, NOW);

        assertThatThrownBy(() -> tag.convertTo(Facet.DOCUMENT_TYPE, NOW)).hasFieldOrPropertyWithValue("code",
                "label.convert.unsupported");
        tag.convertTo(Facet.TOPIC, NOW);

        assertThat(tag.facet()).isEqualTo(Facet.TOPIC);
        assertThatThrownBy(() -> tag.convertTo(Facet.ORGANIZATION, NOW)).hasFieldOrPropertyWithValue("code",
                "label.convert.unsupported");
    }

    @Test
    void retiredLabelsStopAcceptingAssignmentsAndAliasCountIsBounded() {
        Label telekom = organization("Telekom");
        telekom.retire(NOW);
        telekom.retire(NOW);

        assertThat(telekom.acceptsAssignments()).isFalse();
        List<String> aliases = new ArrayList<>();
        for (int i = 0; i <= Label.MAX_ALIASES; i++) {
            aliases.add("Alias " + i);
        }
        assertThatThrownBy(() -> telekom.replaceAliases(aliases, NOW)).hasFieldOrPropertyWithValue("code",
                "label.alias.tooMany");
        assertThatThrownBy(() -> Facet.parse("COLOR")).hasFieldOrPropertyWithValue("code", "label.facet.invalid");
    }
}
