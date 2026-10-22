package dev.aegis.documents.application;

import dev.aegis.documents.application.api.DerivationPortability;
import dev.aegis.documents.domain.Document;
import dev.aegis.documents.domain.DocumentRepository;
import dev.aegis.documents.domain.pages.Derivation;
import dev.aegis.documents.domain.pages.Derivation.Range;
import dev.aegis.documents.domain.pages.Derivation.SourceKind;
import dev.aegis.documents.domain.pages.DerivationRepository;
import dev.aegis.shared.Actor;
import dev.aegis.shared.DocumentId;
import dev.aegis.shared.HouseholdAccess;
import dev.aegis.shared.InvalidInputException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Exports and restores the source relationships of documents created by the page editor (ADR-0024). A restored
 * range whose source is not stored here keeps its pages and names a deleted source, as after a permanent deletion.
 */
public class DerivationPortabilityService implements DerivationPortability {
    private final DocumentRepository documents;
    private final DerivationRepository derivations;
    private final HouseholdAccess access;

    public DerivationPortabilityService(DocumentRepository documents, DerivationRepository derivations,
            HouseholdAccess access) {
        this.documents = documents;
        this.derivations = derivations;
        this.access = access;
    }

    @Override
    public List<DerivationRecord> exportAll(Actor actor) {
        access.requireReader(actor);
        return derivations.findAll().stream().map(derivation -> new DerivationRecord(derivation.document(),
                derivation.pageEdit(), derivation.createdBy(), derivation.createdAt(), derivation.ranges().stream()
                        .map(range -> new RangeRecord(range.firstPage(), range.lastPage(), range.kind().name(),
                                range.source(), range.firstSourcePage(), range.lastSourcePage(), range.rotation()))
                        .toList()))
                .toList();
    }

    @Override
    public ImportResult importDerivation(Actor actor, DerivationRecord record) {
        access.requireMember(actor);
        if (documents.findById(record.document()).isEmpty()) {
            return new ImportResult(ImportOutcome.DOCUMENT_MISSING, 0);
        }
        if (derivations.findByDocument(record.document()).isPresent()) {
            return new ImportResult(ImportOutcome.ALREADY_PRESENT, 0);
        }
        Set<DocumentId> stored = documents.findByIds(record.ranges().stream().map(RangeRecord::source)
                .filter(Objects::nonNull).distinct().toList()).stream().map(Document::id)
                .collect(Collectors.toSet());
        List<Range> ranges = new ArrayList<>();
        int cleared = 0;
        for (RangeRecord range : record.ranges()) {
            DocumentId source = range.source();
            if (source != null && !stored.contains(source)) {
                source = null;
                cleared++;
            }
            ranges.add(range(record.document(), range, source));
        }
        derivations.insertIfAbsent(derivation(record, ranges));
        return new ImportResult(ImportOutcome.IMPORTED, cleared);
    }

    private static Range range(DocumentId document, RangeRecord range, DocumentId source) {
        try {
            return new Range(range.firstPage(), range.lastPage(), SourceKind.valueOf(range.sourceKind()), source,
                    range.firstSourcePage(), range.lastSourcePage(), range.rotation());
        } catch (IllegalArgumentException | NullPointerException invalid) {
            throw new InvalidInputException("export.derivation.invalid",
                    "Invalid page range in the derivation of document " + document + ": " + invalid.getMessage());
        }
    }

    private static Derivation derivation(DerivationRecord record, List<Range> ranges) {
        if (!Set.of(0, 90, 180, 270).containsAll(ranges.stream().map(Range::rotation).toList())) {
            throw new InvalidInputException("export.derivation.invalid",
                    "Invalid rotation in the derivation of document " + record.document());
        }
        try {
            return new Derivation(record.document(), record.pageEdit(), record.createdBy(), record.createdAt(),
                    ranges);
        } catch (IllegalArgumentException | NullPointerException invalid) {
            throw new InvalidInputException("export.derivation.invalid",
                    "Invalid derivation of document " + record.document() + ": " + invalid.getMessage());
        }
    }
}
