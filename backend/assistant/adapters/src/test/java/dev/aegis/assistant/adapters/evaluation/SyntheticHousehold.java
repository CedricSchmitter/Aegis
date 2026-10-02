package dev.aegis.assistant.adapters.evaluation;

import dev.aegis.contracts.application.api.ContractQueries;
import dev.aegis.contracts.application.api.ContractViews.ContractSummary;
import dev.aegis.contracts.application.api.ContractViews.ContractView;
import dev.aegis.documents.application.api.ArchivePdf;
import dev.aegis.documents.application.api.CatalogQueries;
import dev.aegis.documents.application.api.CategoryListing;
import dev.aegis.documents.application.api.CategoryRef;
import dev.aegis.documents.application.api.CategoryView;
import dev.aegis.documents.application.api.ClassificationView;
import dev.aegis.documents.application.api.ContentSearchState;
import dev.aegis.documents.application.api.DocumentQueries;
import dev.aegis.documents.application.api.DocumentSearchRequest;
import dev.aegis.documents.application.api.DocumentSearchResult;
import dev.aegis.documents.application.api.DocumentSummary;
import dev.aegis.documents.application.api.DocumentTextQuery;
import dev.aegis.documents.application.api.DocumentThumbnail;
import dev.aegis.documents.application.api.FacetCounts;
import dev.aegis.documents.application.api.FilenameView;
import dev.aegis.documents.application.api.LabelChip;
import dev.aegis.documents.application.api.LabelView;
import dev.aegis.documents.application.api.OriginalContent;
import dev.aegis.documents.application.api.ProcessingInfo;
import dev.aegis.documents.application.api.TextRecognitionView;
import dev.aegis.planning.application.api.TaskQueries;
import dev.aegis.planning.application.api.TaskSummary;
import dev.aegis.shared.Actor;
import dev.aegis.shared.ContractId;
import dev.aegis.shared.DocumentId;
import dev.aegis.shared.MemberId;
import dev.aegis.shared.NotFoundException;
import dev.aegis.shared.TaskId;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;
import java.util.regex.Pattern;

/**
 * The reference set's documents as a household that holds nothing else: folders, the built-in labels plus the
 * organizations the documents name, and documents with their text by page. No contracts or tasks. The search follows
 * the application's semantics (AND across label groups, OR within a group unless ALL, folder scope with subfolders)
 * and approximates the archive's word search: every word must occur in the title or text, ignoring case and accents,
 * and a number among several words must start a term.
 */
final class SyntheticHousehold implements DocumentQueries, CatalogQueries, DocumentTextQuery {
    /** The application's built-in labels (V2_2 migration) with their stable ids and German and English names. */
    private static final List<String[]> BUILTIN = List.of(
            new String[] {"11111111-0000-4000-8000-000000000001", "DOCUMENT_TYPE", "invoice", "Rechnung", "Invoice"},
            new String[] {"11111111-0000-4000-8000-000000000002", "DOCUMENT_TYPE", "contract", "Vertrag", "Contract"},
            new String[] {"11111111-0000-4000-8000-000000000003", "DOCUMENT_TYPE", "insurancePolicy",
                "Versicherungspolice", "Insurance policy"},
            new String[] {"11111111-0000-4000-8000-000000000004", "DOCUMENT_TYPE", "accountStatement", "Kontoauszug",
                "Account statement"},
            new String[] {"11111111-0000-4000-8000-000000000005", "DOCUMENT_TYPE", "officialNotice", "Bescheid",
                "Official notice"},
            new String[] {"11111111-0000-4000-8000-000000000006", "DOCUMENT_TYPE", "letter", "Schreiben", "Letter"},
            new String[] {"11111111-0000-4000-8000-000000000007", "DOCUMENT_TYPE", "receipt", "Beleg", "Receipt"},
            new String[] {"11111111-0000-4000-8000-000000000008", "DOCUMENT_TYPE", "paymentReminder", "Mahnung",
                "Payment reminder"},
            new String[] {"11111111-0000-4000-8000-000000000009", "DOCUMENT_TYPE", "certificate", "Bescheinigung",
                "Certificate"},
            new String[] {"11111111-0000-4000-8000-000000000010", "DOCUMENT_TYPE", "warranty", "Garantie", "Warranty"},
            new String[] {"11111111-0000-4000-8000-000000000011", "DOCUMENT_TYPE", "manual", "Anleitung", "Manual"},
            new String[] {"11111111-0000-4000-8000-000000000012", "DOCUMENT_TYPE", "payslip", "Gehaltsabrechnung",
                "Payslip"},
            new String[] {"11111111-0000-4000-8000-000000000013", "DOCUMENT_TYPE", "taxDocument", "Steuerunterlage",
                "Tax document"},
            new String[] {"11111111-0000-4000-8000-000000000014", "DOCUMENT_TYPE", "cancellation", "Kündigung",
                "Cancellation"},
            new String[] {"11111111-0000-4000-8000-000000000015", "DOCUMENT_TYPE", "medicalDocument", "Arztbericht",
                "Medical document"},
            new String[] {"22222222-0000-4000-8000-000000000001", "TOPIC", "internet", "Internet", "Internet"},
            new String[] {"22222222-0000-4000-8000-000000000002", "TOPIC", "phone", "Telefon", "Phone"},
            new String[] {"22222222-0000-4000-8000-000000000003", "TOPIC", "electricity", "Strom", "Electricity"},
            new String[] {"22222222-0000-4000-8000-000000000004", "TOPIC", "gas", "Gas", "Gas"},
            new String[] {"22222222-0000-4000-8000-000000000005", "TOPIC", "heating", "Heizung", "Heating"},
            new String[] {"22222222-0000-4000-8000-000000000006", "TOPIC", "water", "Wasser", "Water"},
            new String[] {"22222222-0000-4000-8000-000000000007", "TOPIC", "waste", "Abfall", "Waste"},
            new String[] {"22222222-0000-4000-8000-000000000008", "TOPIC", "insurance", "Versicherung", "Insurance"},
            new String[] {"22222222-0000-4000-8000-000000000009", "TOPIC", "taxes", "Steuern", "Taxes"},
            new String[] {"22222222-0000-4000-8000-000000000010", "TOPIC", "banking", "Bank", "Banking"},
            new String[] {"22222222-0000-4000-8000-000000000011", "TOPIC", "health", "Gesundheit", "Health"},
            new String[] {"22222222-0000-4000-8000-000000000012", "TOPIC", "car", "Auto", "Car"},
            new String[] {"22222222-0000-4000-8000-000000000013", "TOPIC", "housing", "Wohnen", "Housing"},
            new String[] {"22222222-0000-4000-8000-000000000014", "TOPIC", "work", "Arbeit", "Work"},
            new String[] {"22222222-0000-4000-8000-000000000015", "TOPIC", "purchases", "Anschaffungen", "Purchases"},
            new String[] {"22222222-0000-4000-8000-000000000016", "TOPIC", "broadcastingFee", "Rundfunkbeitrag",
                "Broadcasting fee"},
            new String[] {"22222222-0000-4000-8000-000000000017", "TOPIC", "pension", "Vorsorge", "Pension"},
            new String[] {"22222222-0000-4000-8000-000000000018", "TOPIC", "travel", "Reisen", "Travel"},
            new String[] {"22222222-0000-4000-8000-000000000019", "TOPIC", "education", "Bildung", "Education"},
            new String[] {"22222222-0000-4000-8000-000000000020", "TOPIC", "pets", "Haustiere", "Pets"});

    private record Folder(UUID id, UUID parentId, String name) {
    }

    private record Stored(DocumentId id, String key, ReferenceSet.Document source, UUID folderId, List<LabelView> labels,
            Instant uploadedAt) {
    }

    private final MemberId uploader;
    private final Map<UUID, Folder> folders = new LinkedHashMap<>();
    private final Map<UUID, LabelView> labels = new LinkedHashMap<>();
    private final Map<DocumentId, Stored> documents = new LinkedHashMap<>();

    SyntheticHousehold(ReferenceSet set, MemberId uploader, Instant uploadedFrom) {
        this.uploader = uploader;
        for (String[] builtin : BUILTIN) {
            UUID id = UUID.fromString(builtin[0]);
            labels.put(id, new LabelView(id, builtin[1], builtin[2], null,
                    builtin[3].equals(builtin[4]) ? List.of(builtin[3]) : List.of(builtin[3], builtin[4]), "ACTIVE",
                    null, 0, 0, 0, 0));
        }
        Folder root = folder(null, set.folderRoot());
        Instant uploadedAt = uploadedFrom;
        for (ReferenceSet.Document document : set.documents()) {
            Folder parent = root;
            UUID folderId = null;
            for (String name : document.folder()) {
                parent = folder(parent.id(), name);
                folderId = parent.id();
            }
            List<LabelView> assigned = document.labels().stream().map(this::label).toList();
            DocumentId id = new DocumentId(UUID.nameUUIDFromBytes(("assistant-eval:" + document.key())
                    .getBytes(StandardCharsets.UTF_8)));
            documents.put(id, new Stored(id, document.key(), document, folderId, assigned, uploadedAt));
            uploadedAt = uploadedAt.plusSeconds(60);
        }
    }

    private Folder folder(UUID parentId, String name) {
        return folders.values().stream().filter(f -> f.name().equals(name) && Objects.equals(f.parentId(),
                parentId)).findFirst().orElseGet(() -> {
                    Folder created = new Folder(UUID.nameUUIDFromBytes(("folder:" + parentId + "/" + name)
                            .getBytes(StandardCharsets.UTF_8)), parentId, name);
                    folders.put(created.id(), created);
                    return created;
                });
    }

    private LabelView label(ReferenceSet.Label wanted) {
        if (wanted.builtin() != null) {
            return labels.values().stream().filter(label -> wanted.builtin().equals(label.builtinKey())).findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("unknown built-in label " + wanted.builtin()));
        }
        return labels.values().stream().filter(label -> wanted.name().equals(label.name())
                && wanted.facet().equals(label.facet())).findFirst().orElseGet(() -> {
                    UUID id = UUID.nameUUIDFromBytes(("label:" + wanted.facet() + "/" + wanted.name())
                            .getBytes(StandardCharsets.UTF_8));
                    LabelView created = new LabelView(id, wanted.facet(), null, wanted.name(), List.of(), "ACTIVE",
                            null, 0, 0, 0, 0);
                    labels.put(id, created);
                    return created;
                });
    }

    /** The reference key of a document id, or empty for an id this household does not hold. */
    Optional<String> keyOf(UUID id) {
        return Optional.ofNullable(documents.get(new DocumentId(id))).map(Stored::key);
    }

    // Documents

    @Override
    public DocumentSummary get(Actor actor, DocumentId id) {
        return summary(stored(id));
    }

    private Stored stored(DocumentId id) {
        Stored stored = documents.get(id);
        if (stored == null) {
            throw new NotFoundException("document", id.value().toString());
        }
        return stored;
    }

    private DocumentSummary summary(Stored stored) {
        List<CategoryRef> path = new ArrayList<>();
        Folder folder = stored.folderId() == null ? null : folders.get(stored.folderId());
        while (folder != null) {
            path.addFirst(new CategoryRef(folder.id(), folder.name()));
            folder = folder.parentId() == null ? null : folders.get(folder.parentId());
        }
        List<LabelChip> chips = stored.labels().stream().map(label -> new LabelChip(label.id(), label.facet(),
                label.builtinKey(), label.name(), LabelChip.SOURCE_OWN, "MANUAL", "CONFIRMED", null, null, null))
                .toList();
        String text = String.join("\n", pages(stored));
        return new DocumentSummary(stored.id(), stored.source().title(), "MANUAL", stored.source().title() + ".pdf",
                "application/pdf", text.length(), null, uploader, stored.uploadedAt(), List.of(),
                new ProcessingInfo("ARCHIVED", 1, null, null, null, false, null, null, stored.uploadedAt()), true, 1,
                new ClassificationView(stored.folderId(), path, chips, List.of(), null, null, 1),
                new FilenameView(stored.source().title() + ".pdf", FilenameView.GENERATED, null), null,
                new TextRecognitionView("RECOGNIZED", text.length(), stored.uploadedAt(), null), false, null, null);
    }

    private static List<String> pages(Stored stored) {
        return stored.source().pages().stream().map(lines -> String.join("\n", lines)).toList();
    }

    @Override
    public DocumentSearchResult search(Actor actor, DocumentSearchRequest request) {
        String text = request.text() == null ? "" : request.text().strip();
        Predicate<Stored> matches = stored -> inScope(stored, request) && hasLabels(stored, request)
                && (text.isEmpty() || wordsMatch(stored, text));
        Comparator<Stored> order = switch (request.sort()) {
            case "OLDEST" -> Comparator.comparing(Stored::uploadedAt);
            case "TITLE" -> Comparator.comparing(stored -> stored.source().title().toLowerCase(Locale.ROOT));
            default -> Comparator.comparing(Stored::uploadedAt).reversed();
        };
        List<Stored> found = documents.values().stream().filter(matches).sorted(order).toList();
        int from = Math.min(found.size(), request.page() * request.size());
        List<DocumentSummary> items = found.subList(from, Math.min(found.size(), from + request.size())).stream()
                .map(this::summary).toList();
        return new DocumentSearchResult(items, text.isEmpty() ? ContentSearchState.NOT_APPLIED
                : ContentSearchState.READY, documents.size(), found.size(), request.page(), request.size(),
                FacetCounts.EMPTY, 0);
    }

    private boolean inScope(Stored stored, DocumentSearchRequest request) {
        if (request.unsortedOnly()) {
            return stored.folderId() == null;
        }
        if (request.categoryId() == null) {
            return true;
        }
        UUID folder = stored.folderId();
        while (folder != null) {
            if (folder.equals(request.categoryId())) {
                return true;
            }
            folder = request.includeSubfolders() ? folders.get(folder).parentId() : null;
        }
        return false;
    }

    private static boolean hasLabels(Stored stored, DocumentSearchRequest request) {
        Set<UUID> own = new HashSet<>();
        stored.labels().forEach(label -> own.add(label.id()));
        return any(own, request.documentTypes()) && any(own, request.organizations())
                && (request.topicsMatchAll() ? own.containsAll(request.topics()) : any(own, request.topics()))
                && (request.tagsMatchAll() ? own.containsAll(request.tags()) : any(own, request.tags()));
    }

    private static boolean any(Set<UUID> own, List<UUID> wanted) {
        return wanted.isEmpty() || wanted.stream().anyMatch(own::contains);
    }

    /**
     * Title fragment as in the application's catalog, or the archive's simple text search: every word must occur
     * in the title or text; a number among several words must start a term instead of occurring inside one.
     */
    private static boolean wordsMatch(Stored stored, String query) {
        String title = fold(stored.source().title());
        if (title.contains(fold(query))) {
            return true;
        }
        String haystack = title + "\n" + fold(String.join("\n", pages(stored)));
        List<String> words = List.of(fold(query).split("\\s+"));
        for (String word : words) {
            boolean bounded = words.size() > 1 && word.chars().allMatch(Character::isDigit);
            if (bounded ? !haystack.matches("(?s).*(^|[^\\p{Alnum}])" + Pattern.quote(word) + ".*")
                    : !haystack.contains(word)) {
                return false;
            }
        }
        return true;
    }

    private static String fold(String text) {
        String lower = text.toLowerCase(Locale.ROOT).replace("ß", "ss");
        return Normalizer.normalize(lower, Normalizer.Form.NFD).replaceAll("\\p{M}", "");
    }

    @Override
    public List<DocumentSummary> trashed(Actor actor) {
        return List.of();
    }

    @Override
    public OriginalContent original(Actor actor, DocumentId id) {
        throw new UnsupportedOperationException("originals are not part of the evaluation");
    }

    @Override
    public ArchivePdf archivePdf(Actor actor, DocumentId id) {
        throw new UnsupportedOperationException("archive PDFs are not part of the evaluation");
    }

    @Override
    public DocumentThumbnail thumbnail(Actor actor, DocumentId id) {
        throw new UnsupportedOperationException("thumbnails are not part of the evaluation");
    }

    @Override
    public DocumentText text(Actor actor, DocumentId id) {
        Stored stored = stored(id);
        return new DocumentText(id, stored.source().title(), pages(stored), true);
    }

    // Catalog

    @Override
    public CategoryListing categories(Actor actor) {
        Map<UUID, Long> counts = new HashMap<>();
        long unsorted = 0;
        for (Stored stored : documents.values()) {
            if (stored.folderId() == null) {
                unsorted++;
            } else {
                counts.merge(stored.folderId(), 1L, Long::sum);
            }
        }
        List<CategoryView> views = folders.values().stream().map(folder -> new CategoryView(folder.id(),
                folder.parentId(), folder.name(), List.of(), List.of(), counts.getOrDefault(folder.id(), 0L), 1))
                .toList();
        return new CategoryListing(views, unsorted, documents.size());
    }

    @Override
    public List<LabelView> labels(Actor actor) {
        Map<UUID, Long> counts = new HashMap<>();
        documents.values().forEach(stored -> stored.labels().forEach(label -> counts.merge(label.id(), 1L, Long::sum)));
        return labels.values().stream().map(label -> new LabelView(label.id(), label.facet(), label.builtinKey(),
                label.name(), label.aliases(), label.status(), null, counts.getOrDefault(label.id(), 0L), 0, 0, 1))
                .toList();
    }

    @Override
    public Optional<LabelView> resolveLabel(Actor actor, String facet, String name) {
        throw new UnsupportedOperationException("the assistant does not resolve labels");
    }

    ContractQueries contracts() {
        return new ContractQueries() {
            @Override
            public List<ContractSummary> list(Actor actor) {
                return List.of();
            }

            @Override
            public ContractView get(Actor actor, ContractId id) {
                throw new NotFoundException("contract", id.value().toString());
            }

            @Override
            public List<ContractSummary> forDocument(Actor actor, DocumentId documentId) {
                return List.of();
            }

            @Override
            public List<ContractSummary> trashed(Actor actor) {
                return List.of();
            }
        };
    }

    TaskQueries tasks() {
        return new TaskQueries() {
            @Override
            public TaskSummary get(Actor actor, TaskId id) {
                throw new NotFoundException("task", id.value().toString());
            }

            @Override
            public List<TaskSummary> list(Actor actor, boolean includeDone) {
                return List.of();
            }

            @Override
            public List<TaskSummary> forDocument(Actor actor, DocumentId documentId) {
                return List.of();
            }

            @Override
            public List<TaskSummary> trashed(Actor actor) {
                return List.of();
            }

            @Override
            public TodayView today(Actor actor) {
                throw new UnsupportedOperationException("the assistant does not read the day view");
            }
        };
    }
}
