package dev.aegis.documents.application.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.aegis.documents.application.api.DocumentTextQuery.DocumentText;
import dev.aegis.shared.DocumentId;
import java.util.List;
import org.junit.jupiter.api.Test;

class DocumentTextTest {
    private static final DocumentText PAGED = new DocumentText(DocumentId.random(), "Vertrag",
            List.of("eins", "", "drei"), true);

    @Test
    void positionsInTheJoinedTextNameTheirPage() {
        assertThat(PAGED.text()).isEqualTo("eins\n\ndrei");
        assertThat(PAGED.pageStart(1)).isZero();
        assertThat(PAGED.pageStart(2)).isEqualTo(5);
        assertThat(PAGED.pageStart(3)).isEqualTo(6);
        assertThat(PAGED.pageAt(0)).hasValue(1);
        assertThat(PAGED.pageAt(4)).as("the separator belongs to the page it ends").hasValue(1);
        assertThat(PAGED.pageAt(5)).hasValue(2);
        assertThat(PAGED.pageAt(6)).hasValue(3);
        assertThat(PAGED.pageAt(PAGED.text().length())).hasValue(3);
        assertThatThrownBy(() -> PAGED.pageStart(4)).isInstanceOf(IndexOutOfBoundsException.class);
    }

    @Test
    void aTextWithoutPagesNamesNoPage() {
        DocumentText unpaged = DocumentText.unpaged(DocumentId.random(), "Foto", "Seite eins\fSeite zwei");

        assertThat(unpaged.text()).isEqualTo("Seite eins\fSeite zwei");
        assertThat(unpaged.pageAt(15)).isEmpty();
        assertThatThrownBy(() -> new DocumentText(DocumentId.random(), "Foto", List.of("a", "b"), false))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new DocumentText(DocumentId.random(), "Foto", List.of(), true))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
