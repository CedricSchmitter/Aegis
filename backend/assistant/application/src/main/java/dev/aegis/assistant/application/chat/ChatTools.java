package dev.aegis.assistant.application.chat;

import java.util.Set;

/** Names of the read-only tools the household assistant may use; the tools themselves are adapters. */
public final class ChatTools {
    public static final String HOUSEHOLD_CATALOG = "household_catalog";
    public static final String SEARCH_DOCUMENTS = "search_documents";
    public static final String READ_DOCUMENT = "read_document";
    public static final String LIST_CONTRACTS = "list_contracts";
    public static final String READ_CONTRACT = "read_contract";
    public static final String LIST_TASKS = "list_tasks";

    static final Set<String> GRANTED = Set.of(HOUSEHOLD_CATALOG, SEARCH_DOCUMENTS, READ_DOCUMENT, LIST_CONTRACTS,
            READ_CONTRACT, LIST_TASKS);

    private ChatTools() {
    }
}
