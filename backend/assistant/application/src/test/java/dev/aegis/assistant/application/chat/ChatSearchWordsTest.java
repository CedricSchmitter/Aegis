package dev.aegis.assistant.application.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import dev.aegis.shared.InvalidInputException;
import org.junit.jupiter.api.Test;

class ChatSearchWordsTest {
    @Test
    void aSearchIsSplitIntoDistinctWordsKeptAsTyped() {
        assertThat(ChatService.searchWords("  Hotel   Frankfurt\thotel 100% ")).containsExactly("Hotel", "Frankfurt",
                "hotel", "100%");
        assertThat(ChatService.searchWords("MRT MRT")).containsExactly("MRT");
    }

    @Test
    void blankTextHasNoWords() {
        assertThat(ChatService.searchWords(null)).isEmpty();
        assertThat(ChatService.searchWords("   ")).isEmpty();
    }

    @Test
    void atMostEightWordsAreUsed() {
        assertThat(ChatService.searchWords("a b c d e f g h i j")).containsExactly("a", "b", "c", "d", "e", "f", "g",
                "h");
    }

    @Test
    void anOverlongSearchIsRefusedRatherThanCut() {
        assertThat(catchThrowableOfType(InvalidInputException.class, () -> ChatService.searchWords("x".repeat(201)))
                .code()).isEqualTo("validation.failed");
        assertThat(ChatService.searchWords(" " + "x".repeat(200) + " ")).hasSize(1);
    }
}
