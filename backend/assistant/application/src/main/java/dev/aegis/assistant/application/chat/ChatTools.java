package dev.aegis.assistant.application.chat;

/**
 * Names of the read-only tools the household assistant may use; the tools themselves are adapters. Which of them a
 * run is granted depends on the {@link ChatRetrieval}.
 */
public final class ChatTools {
    public static final String HOUSEHOLD_CATALOG = "household_catalog";
    public static final String SEARCH_DOCUMENTS = "search_documents";
    public static final String SEARCH_DOCUMENT_TEXT = "search_document_text";
    public static final String READ_DOCUMENT = "read_document";
    public static final String LIST_CONTRACTS = "list_contracts";
    public static final String READ_CONTRACT = "read_contract";
    public static final String LIST_TASKS = "list_tasks";

    private ChatTools() {
    }
}
