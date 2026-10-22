package dev.aegis.assistant.application.chat;

import java.util.Set;

/**
 * How the household assistant finds documents. {@link #LABELS_AND_TEXT} is the product behavior: the model may narrow
 * a search with the household's folders and labels and searches with words as well. {@link #TEXT_ONLY} withholds the
 * catalog and every folder and label filter, so documents are found by words in their title and archived text alone;
 * it exists to compare both ways of retrieval with the same reference questions (issue #25).
 */
public enum ChatRetrieval {
    LABELS_AND_TEXT(Set.of(ChatTools.HOUSEHOLD_CATALOG, ChatTools.SEARCH_DOCUMENTS, ChatTools.READ_DOCUMENT,
            ChatTools.FIND_IN_DOCUMENT, ChatTools.LIST_CONTRACTS, ChatTools.READ_CONTRACT, ChatTools.LIST_TASKS)),
    TEXT_ONLY(Set.of(ChatTools.SEARCH_DOCUMENT_TEXT, ChatTools.READ_DOCUMENT, ChatTools.FIND_IN_DOCUMENT,
            ChatTools.LIST_CONTRACTS, ChatTools.READ_CONTRACT, ChatTools.LIST_TASKS));

    private final Set<String> tools;

    ChatRetrieval(Set<String> tools) {
        this.tools = tools;
    }

    Set<String> tools() {
        return tools;
    }
}
