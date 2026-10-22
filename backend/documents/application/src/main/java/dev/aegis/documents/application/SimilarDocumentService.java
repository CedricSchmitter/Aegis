package dev.aegis.documents.application;

import dev.aegis.documents.application.api.DocumentSummary;
import dev.aegis.documents.application.api.SimilarDocumentQueries;
import dev.aegis.documents.domain.Document;
import dev.aegis.documents.domain.DocumentRepository;
import dev.aegis.documents.domain.text.ArchiveTextCheckRepository;
import dev.aegis.documents.domain.text.TextFingerprint;
import dev.aegis.shared.Actor;
import dev.aegis.shared.DocumentId;
import dev.aegis.shared.HouseholdAccess;
import dev.aegis.shared.NotFoundException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Compares the stored fingerprints of the archive texts with each other (ADR-0028). A household collection of a few
 * hundred to a thousand documents is compared in memory in one pass; nothing is stored, so a hint disappears as soon
 * as a document is trashed or its text changes.
 */
public class SimilarDocumentService implements SimilarDocumentQueries {
    static final int MAX_SIMILAR = 10;
    private static final Comparator<Document> UPLOAD_ORDER = Comparator.comparing(Document::uploadedAt)
            .thenComparing(document -> document.id().value());

    private final DocumentRepository documents;
    private final ArchiveTextCheckRepository checks;
    private final DocumentPresenter presenter;
    private final HouseholdAccess access;

    public SimilarDocumentService(DocumentRepository documents, ArchiveTextCheckRepository checks,
            DocumentPresenter presenter, HouseholdAccess access) {
        this.documents = documents;
        this.checks = checks;
        this.presenter = presenter;
        this.access = access;
    }

    @Override
    public List<DocumentSummary> similarTo(Actor actor, DocumentId id) {
        access.requireMember(actor);
        documents.findById(id).orElseThrow(() -> new NotFoundException("document", id.toString()));
        Map<DocumentId, TextFingerprint> fingerprints = checks.findFingerprints();
        TextFingerprint own = fingerprints.get(id);
        if (own == null || !own.comparable()) {
            return List.of();
        }
        List<DocumentId> similar = fingerprints.entrySet().stream()
                .filter(entry -> !entry.getKey().equals(id) && own.nearDuplicateOf(entry.getValue()))
                .sorted(Comparator.comparingDouble(
                        (Map.Entry<DocumentId, TextFingerprint> entry) -> own.wordSimilarity(entry.getValue()))
                        .reversed().thenComparing(entry -> entry.getKey().value()))
                .limit(MAX_SIMILAR).map(Map.Entry::getKey).toList();
        Map<DocumentId, Document> found = byId(similar);
        return similar.stream().filter(found::containsKey).map(found::get).map(presenter::summarize).toList();
    }

    @Override
    public List<SimilarPair> similarPairs(Actor actor) {
        access.requireReader(actor);
        List<Map.Entry<DocumentId, TextFingerprint>> comparable = checks.findFingerprints().entrySet().stream()
                .filter(entry -> entry.getValue().comparable()).toList();
        List<DocumentId[]> pairs = new ArrayList<>();
        Set<DocumentId> involved = new HashSet<>();
        for (int i = 0; i < comparable.size(); i++) {
            for (int j = i + 1; j < comparable.size(); j++) {
                if (comparable.get(i).getValue().nearDuplicateOf(comparable.get(j).getValue())) {
                    pairs.add(new DocumentId[] {comparable.get(i).getKey(), comparable.get(j).getKey()});
                    involved.add(comparable.get(i).getKey());
                    involved.add(comparable.get(j).getKey());
                }
            }
        }
        Map<DocumentId, Document> found = byId(involved);
        List<SimilarPair> result = new ArrayList<>();
        for (DocumentId[] pair : pairs) {
            Document a = found.get(pair[0]);
            Document b = found.get(pair[1]);
            if (a == null || b == null) {
                continue;
            }
            boolean aFirst = UPLOAD_ORDER.compare(a, b) < 0;
            Document first = aFirst ? a : b;
            Document second = aFirst ? b : a;
            result.add(new SimilarPair(first.id(), first.title(), second.id(), second.title()));
        }
        result.sort(Comparator.comparing((SimilarPair pair) -> found.get(pair.first()), UPLOAD_ORDER)
                .thenComparing(pair -> found.get(pair.second()), UPLOAD_ORDER));
        return List.copyOf(result);
    }

    private Map<DocumentId, Document> byId(Collection<DocumentId> ids) {
        return documents.findByIds(ids).stream().collect(Collectors.toMap(Document::id, Function.identity()));
    }
}
