package dev.aegis.assistant.adapters.tools;

import dev.aegis.contracts.application.api.ContractViews.ConflictView;
import dev.aegis.contracts.application.api.ContractViews.ContractView;
import dev.aegis.contracts.application.api.ContractViews.DeadlineView;
import dev.aegis.contracts.application.api.ContractViews.FactView;
import dev.aegis.contracts.application.api.ContractViews.SourceView;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Contract views as tool results. Values are the canonical, locale-independent forms ({@code 2028-01-01},
 * {@code P3M}, {@code 29.90 EUR}); fact sources name the document, page and quote they were read from.
 */
final class ContractJson {
    private ContractJson() {
    }

    static Map<String, Object> view(ContractView contract) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("id", contract.id().toString());
        view.put("name", contract.name());
        view.put("counterparty", contract.counterparty());
        view.put("status", contract.status());
        view.put("inTrash", contract.trash() != null);
        view.put("evaluatedOn", text(contract.evaluatedOn()));
        view.put("cancellation", deadline(contract.deadline()));
        List<Map<String, Object>> facts = new ArrayList<>();
        for (FactView fact : contract.facts()) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("factId", fact.id().toString());
            entry.put("type", fact.type());
            entry.put("value", fact.value());
            entry.put("validFrom", text(fact.validFrom()));
            entry.put("validUntil", text(fact.validUntil()));
            entry.put("reviewState", fact.reviewState());
            entry.put("effective", fact.effective());
            entry.put("superseded", fact.supersededBy() != null);
            entry.put("source", source(fact.source()));
            if (fact.note() != null) {
                entry.put("note", fact.note());
            }
            facts.add(entry);
        }
        view.put("facts", facts);
        List<Map<String, Object>> conflicts = new ArrayList<>();
        for (ConflictView conflict : contract.conflicts()) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("type", conflict.type());
            entry.put("kind", conflict.kind());
            entry.put("establishedFactId", conflict.establishedFactId() == null ? null
                    : conflict.establishedFactId().toString());
            entry.put("conflictingFactIds", conflict.conflictingFactIds().stream().map(UUID::toString).toList());
            conflicts.add(entry);
        }
        view.put("contradictions", conflicts);
        return view;
    }

    private static Map<String, Object> source(SourceView source) {
        if (source == null) {
            return null;
        }
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("documentId", source.documentId() == null ? null : source.documentId().toString());
        view.put("documentTitle", source.documentTitle());
        view.put("documentInTrash", source.documentTrashed());
        view.put("documentDeleted", source.documentDeleted());
        view.put("page", source.page());
        view.put("quote", source.quote());
        return view;
    }

    static Map<String, Object> deadline(DeadlineView deadline) {
        if (deadline == null) {
            return null;
        }
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("determined", deadline.determined());
        view.put("deadline", text(deadline.deadline()));
        view.put("contractEnd", text(deadline.contractEnd()));
        view.put("basis", deadline.basis());
        if (!deadline.missing().isEmpty()) {
            view.put("missing", deadline.missing());
        }
        if (!deadline.contradicting().isEmpty()) {
            view.put("contradicting", deadline.contradicting());
        }
        return view;
    }

    static String text(Object value) {
        return value == null ? null : value.toString();
    }
}
