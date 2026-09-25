package dev.aegis.documents.application;

import dev.aegis.documents.application.api.AutomaticClassification;
import dev.aegis.documents.application.api.CategoryRef;
import dev.aegis.documents.application.api.ClassificationView;
import dev.aegis.documents.application.api.DocumentAnalysisCommands;
import dev.aegis.documents.application.api.AnalysisRecord;
import dev.aegis.documents.application.api.DocumentSummary;
import dev.aegis.documents.application.api.ImportDocumentAnalysis;
import dev.aegis.documents.application.api.LabelCommands;
import dev.aegis.documents.application.api.ProposeClassification;
import dev.aegis.documents.application.api.SubjectView;
import dev.aegis.documents.application.ports.AnalysisRuns;
import dev.aegis.documents.application.api.ContentUnavailableException;
import dev.aegis.documents.application.ports.ArchiveRejectedException;
import dev.aegis.documents.application.ports.ArchiveUnavailableException;
import dev.aegis.documents.application.ports.CatalogLock;
import dev.aegis.documents.application.ports.DocumentCatalog;
import dev.aegis.documents.application.ports.HouseholdPersons;
import dev.aegis.documents.application.ports.PaperlessArchive;
import dev.aegis.documents.domain.Document;
import dev.aegis.documents.domain.DocumentRepository;
import dev.aegis.documents.domain.ProcessingState;
import dev.aegis.documents.domain.Subject;
import dev.aegis.documents.domain.analysis.AnalysisTypes.DateStatus;
import dev.aegis.documents.domain.analysis.AnalysisTypes.Decision;
import dev.aegis.documents.domain.analysis.AnalysisTypes.Flag;
import dev.aegis.documents.domain.analysis.AnalysisTypes.Status;
import dev.aegis.documents.domain.analysis.AnalysisTypes.Trigger;
import dev.aegis.documents.domain.analysis.AnalysisValues.DocumentDate;
import dev.aegis.documents.domain.analysis.AnalysisValues.LabelCandidate;
import dev.aegis.documents.domain.analysis.AnalysisValues;
import dev.aegis.documents.domain.analysis.AnalysisValues.SubjectSuggestion;
import dev.aegis.documents.domain.analysis.ClassificationPolicy;
import dev.aegis.documents.domain.analysis.DocumentAnalysis;
import dev.aegis.documents.domain.analysis.DocumentAnalysisRepository;
import dev.aegis.documents.domain.analysis.Evidence;
import dev.aegis.documents.domain.catalog.CatalogNames;
import dev.aegis.documents.domain.catalog.CategoryRepository;
import dev.aegis.documents.domain.catalog.Facet;
import dev.aegis.documents.domain.catalog.Label;
import dev.aegis.documents.domain.catalog.LabelId;
import dev.aegis.documents.domain.catalog.LabelRepository;
import dev.aegis.documents.domain.catalog.LabelStatus;
import dev.aegis.documents.domain.catalog.LabelVocabulary;
import dev.aegis.documents.domain.classification.ClassificationRepository;
import dev.aegis.documents.domain.classification.DocumentClassification;
import dev.aegis.documents.domain.classification.LabelRef;
import dev.aegis.documents.domain.naming.FriendlyFilename;
import dev.aegis.documents.domain.placement.PlacementTarget;
import dev.aegis.shared.Actor;
import dev.aegis.shared.DocumentId;
import dev.aegis.shared.HouseholdAccess;
import dev.aegis.shared.InvalidInputException;
import dev.aegis.shared.MemberId;
import dev.aegis.shared.NotFoundException;
import dev.aegis.shared.StaleVersionException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Automatic document analysis (issue #23, ADR-0004): requesting runs when an upload is archived or a member asks,
 * assessing the classifier's proposal under {@link ClassificationPolicy}, applying it once, and the member decisions
 * on generated titles, new names, subject suggestions and filenames.
 *
 * <p>Commands and {@link #apply} are wrapped in a transaction by the composition root; {@link #input} and
 * {@link #assess} read the archive and are called outside transactions.
 */
public class DocumentAnalysisService implements DocumentAnalysisCommands, AutomaticClassification, AnalysisTrigger,
        ImportDocumentAnalysis {
    public static final int MAX_TITLE = 120;
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");
    private static final Pattern INVISIBLE = Pattern.compile("[\\p{Cc}\\p{Cf}\\p{Co}\\p{Cn}]");
    private static final Set<Facet> CLASSIFIER_FACETS = EnumSet.of(Facet.DOCUMENT_TYPE, Facet.ORGANIZATION,
            Facet.TOPIC);

    private final DocumentRepository documents;
    private final DocumentAnalysisRepository analyses;
    private final ClassificationRepository classifications;
    private final LabelRepository labels;
    private final CategoryRepository categories;
    private final DocumentCatalog catalog;
    private final ProposeClassification proposals;
    private final LabelCommands labelCommands;
    private final CatalogLock lock;
    private final PaperlessArchive archive;
    private final AnalysisRuns runs;
    private final ClassificationViews classificationViews;
    private final DocumentPresenter presenter;
    private final HouseholdPersons persons;
    private final HouseholdAccess access;
    private final AnalysisSettings settings;
    private final Clock clock;

    public DocumentAnalysisService(DocumentRepository documents, DocumentAnalysisRepository analyses,
            ClassificationRepository classifications, LabelRepository labels, CategoryRepository categories,
            DocumentCatalog catalog, ProposeClassification proposals, LabelCommands labelCommands, CatalogLock lock,
            PaperlessArchive archive, AnalysisRuns runs, ClassificationViews classificationViews,
            DocumentPresenter presenter, HouseholdPersons persons, HouseholdAccess access, AnalysisSettings settings,
            Clock clock) {
        this.documents = documents;
        this.analyses = analyses;
        this.classifications = classifications;
        this.labels = labels;
        this.categories = categories;
        this.catalog = catalog;
        this.proposals = proposals;
        this.labelCommands = labelCommands;
        this.lock = lock;
        this.archive = archive;
        this.runs = runs;
        this.classificationViews = classificationViews;
        this.presenter = presenter;
        this.persons = persons;
        this.access = access;
        this.settings = settings;
        this.clock = clock;
    }

    // Trigger -------------------------------------------------------------------------------------------------------

    /**
     * Called in the archival transaction: an upload marked for automatic analysis gets its run now that the archive
     * holds the OCR text. The run is requested on behalf of the uploader. Duplicate archival events find the
     * analysis already requested and do nothing.
     */
    @Override
    public void archived(Document document) {
        if (document.isTrashed()) {
            // Waits until a member restores the document; restoring calls this trigger again.
            return;
        }
        Optional<DocumentAnalysis> found = analyses.findById(document.id());
        if (found.isEmpty() || !found.get().awaitsArchive()) {
            return;
        }
        DocumentAnalysis analysis = found.get();
        MemberId requester = Optional.ofNullable(analysis.snapshot().requestedBy()).orElse(document.uploadedBy());
        requestRun(document.id(), analysis, new Actor(requester), Trigger.UPLOAD);
    }

    private AnalysisRuns.Outcome requestRun(DocumentId id, DocumentAnalysis analysis, Actor actor, Trigger trigger) {
        long classificationVersion = classifications.findById(id).map(DocumentClassification::version).orElse(0L);
        int generation = analysis.request(actor.memberId(), trigger, clock.instant());
        AnalysisRuns.Outcome outcome = runs.request(actor, id, generation, classificationVersion);
        if (outcome instanceof AnalysisRuns.Unavailable unavailable) {
            analysis.markUnavailable(unavailable.code());
        }
        analyses.save(analysis);
        return outcome;
    }

    // Member requests -----------------------------------------------------------------------------------------------

    @Override
    public DocumentSummary requestAnalysis(Actor actor, DocumentId id, long expectedVersion) {
        access.requireMember(actor);
        Document document = load(id);
        DocumentAnalysis analysis = loadVersion(id, expectedVersion);
        Optional<String> refusal = refusal(document, analysis);
        if (refusal.isPresent()) {
            throw new InvalidInputException(refusal.get(), "The document cannot be analyzed right now");
        }
        requestRun(id, analysis, actor, Trigger.MANUAL);
        return presenter.summarize(load(id));
    }

    @Override
    public BulkResult requestAnalyses(Actor actor, List<DocumentId> ids) {
        access.requireMember(actor);
        Set<DocumentId> distinct = new LinkedHashSet<>(ids == null ? List.of() : ids);
        if (distinct.isEmpty() || distinct.size() > MAX_BULK) {
            throw new InvalidInputException("analysis.bulk.size", "Select 1 to " + MAX_BULK + " documents",
                    Map.of("max", String.valueOf(MAX_BULK)));
        }
        List<DocumentId> requested = new ArrayList<>();
        List<Skipped> skipped = new ArrayList<>();
        for (DocumentId id : distinct) {
            Optional<Document> document = documents.findById(id);
            if (document.isEmpty()) {
                skipped.add(new Skipped(id, "document.notFound"));
                continue;
            }
            DocumentAnalysis analysis = analyses.findById(id).orElseGet(() -> DocumentAnalysis.none(id));
            Optional<String> refusal = refusal(document.get(), analysis);
            if (refusal.isPresent()) {
                skipped.add(new Skipped(id, refusal.get()));
                continue;
            }
            if (requestRun(id, analysis, actor, Trigger.MANUAL) instanceof AnalysisRuns.Unavailable unavailable) {
                skipped.add(new Skipped(id, unavailable.code()));
            } else {
                requested.add(id);
            }
        }
        return new BulkResult(requested, skipped);
    }

    private Optional<String> refusal(Document document, DocumentAnalysis analysis) {
        if (document.isTrashed()) {
            return Optional.of("document.trashed");
        }
        if (document.state() != ProcessingState.ARCHIVED) {
            return Optional.of("document.notArchived");
        }
        if (analysis.status() == Status.REQUESTED) {
            AnalysisRuns.RunStatus run = runs.latest(List.of(AnalysisRuns.key(document.id(),
                    analysis.generation()))).get(AnalysisRuns.key(document.id(), analysis.generation()));
            if (run != null && run.active()) {
                return Optional.of("analysis.pending");
            }
        }
        return Optional.empty();
    }

    // Member decisions ----------------------------------------------------------------------------------------------

    @Override
    public DocumentSummary decideTitle(Actor actor, DocumentId id, boolean accept, long expectedVersion) {
        access.requireMember(actor);
        Document document = loadActive(id);
        DocumentAnalysis analysis = loadVersion(id, expectedVersion);
        Decision before = analysis.titleDecision().orElse(null);
        String generated = analysis.title().orElse(null);
        String result = analysis.decideTitle(accept, actor.memberId());
        if (accept) {
            document.rename(result);
            documents.save(document);
        } else if (before == Decision.APPLIED && result != null && document.title().equals(generated)) {
            // Reverting an applied title restores the previous one; the member decided, so no later run renames it.
            document.rename(result);
            documents.save(document);
        }
        analyses.save(analysis);
        return presenter.summarize(load(id));
    }

    @Override
    public DocumentSummary decideCandidate(Actor actor, DocumentId id, String key, boolean accept,
            long expectedVersion) {
        access.requireMember(actor);
        // Accepting may create a label, which needs the exclusive catalog lock; take it first instead of upgrading.
        if (accept) {
            lock.exclusive();
        } else {
            lock.shared();
        }
        loadActive(id);
        DocumentAnalysis analysis = loadVersion(id, expectedVersion);
        Instant now = clock.instant();
        if (!accept) {
            analysis.decideCandidate(key, false, null, actor.memberId(), now);
            analyses.save(analysis);
            return presenter.summarize(load(id));
        }
        LabelCandidate candidate = analysis.pendingCandidate(key).orElseThrow(() -> new InvalidInputException(
                "analysis.candidate.missing", "There is no pending name suggestion"));
        LabelVocabulary vocabulary = labels.load();
        Optional<Label> existing = vocabulary.findByName(candidate.facet(), candidate.name())
                .map(label -> vocabulary.resolve(label.id()));
        LabelId labelId;
        if (existing.isPresent()) {
            if (!existing.get().acceptsAssignments()) {
                throw new InvalidInputException("label.retired", "Retired labels cannot be assigned",
                        Map.of("labelId", existing.get().id().toString()));
            }
            labelId = existing.get().id();
        } else {
            labelId = new LabelId(labelCommands.create(actor, UUID.randomUUID(), candidate.facet().name(),
                    candidate.name()).id());
        }
        DocumentClassification classification = classifications.findById(id)
                .orElseGet(() -> DocumentClassification.unsorted(id));
        if (classification.keep(new LabelRef(labelId, candidate.facet()), actor.memberId(), now)) {
            classifications.save(classification);
        }
        analysis.decideCandidate(key, true, labelId, actor.memberId(), now);
        analyses.save(analysis);
        return presenter.summarize(load(id));
    }

    @Override
    public DocumentSummary decideSubject(Actor actor, DocumentId id, SubjectView subject, boolean accept,
            long expectedVersion) {
        access.requireMember(actor);
        Document document = loadActive(id);
        DocumentAnalysis analysis = loadVersion(id, expectedVersion);
        Subject decided = Subjects.fromViews(List.of(subject)).iterator().next();
        if (accept) {
            SubjectPersons.requirePersons(Set.of(decided), document.subjects(), persons);
        }
        analysis.decideSubject(decided, accept, actor.memberId(), clock.instant());
        if (accept && !document.subjects().contains(decided)) {
            Set<Subject> next = new LinkedHashSet<>(document.subjects());
            next.add(decided);
            document.changeSubjects(next);
            documents.save(document);
        }
        analyses.save(analysis);
        return presenter.summarize(load(id));
    }

    @Override
    public DocumentSummary setFilename(Actor actor, DocumentId id, String stem, long expectedVersion) {
        access.requireMember(actor);
        loadActive(id);
        DocumentAnalysis analysis = loadVersion(id, expectedVersion);
        String normalized = stem == null || stem.isBlank() ? null : FriendlyFilename.overrideStem(id, stem);
        analysis.overrideFilename(normalized, actor.memberId(), clock.instant());
        analyses.save(analysis);
        return presenter.summarize(load(id));
    }

    // Classifier contract -------------------------------------------------------------------------------------------

    @Override
    public Input input(Actor actor, DocumentId id, int generation) {
        access.requireMember(actor);
        Document document = load(id);
        String text = ocrText(document);
        boolean truncated = text.length() > settings.maxTextChars();
        ClassificationView classification = classificationViews.viewOf(id);
        LabelVocabulary loaded = labels.load();
        List<VocabularyEntry> vocabulary = loaded.all().stream()
                .filter(label -> CLASSIFIER_FACETS.contains(label.facet()) && label.status() == LabelStatus.ACTIVE)
                .map(label -> new VocabularyEntry(label.id().value(), label.facet().name(),
                        label.builtinKey().orElse(null), label.name().orElse(null), List.copyOf(label.aliases())))
                .toList();
        // Trusted catalog context: the folder tree with its implied values, deterministically ordered and bounded.
        FolderCatalog folders = FolderCatalogs.build(categories.load(), loaded, catalog.documentsPerCategory(),
                settings.maxCatalogFolders());
        List<String> currentFolder = classification.path().stream().map(CategoryRef::name).toList();
        return new Input(id, generation, classification.version(), document.title(), document.titleOrigin().name(),
                document.originalFilename(), truncated ? text.substring(0, settings.maxTextChars()) : text,
                truncated, vocabulary, folders, currentFolder, classification.labels(),
                Subjects.toViews(document.subjects()));
    }

    @Override
    public boolean current(Actor actor, DocumentId id, long classificationVersion, int generation) {
        long stored = classifications.findById(id).map(DocumentClassification::version).orElse(0L);
        return stored == classificationVersion
                && analyses.findById(id).map(analysis -> analysis.accepts(generation)).orElse(false);
    }

    @Override
    public Assessment assess(Actor actor, DocumentId id, Proposal proposal) {
        access.requireMember(actor);
        Document document = load(id);
        String text = ocrText(document);
        LabelVocabulary vocabulary = labels.load();
        Set<Flag> flags = EnumSet.noneOf(Flag.class);
        for (String raw : proposal.flags()) {
            try {
                flags.add(Flag.valueOf(raw));
            } catch (IllegalArgumentException | NullPointerException unknown) {
                // Unknown flags from the model are ignored; the schema restricts them anyway.
            }
        }
        if (text.isBlank()) {
            flags.add(Flag.NO_TEXT);
        }
        boolean doubtful = ClassificationPolicy.doubtful(flags);
        Map<UUID, LabelDecision> decisions = new LinkedHashMap<>();
        Map<String, CandidateDecision> candidates = new LinkedHashMap<>();
        UUID correspondent = null;
        decide(proposal.documentType(), Facet.DOCUMENT_TYPE, vocabulary, text, doubtful, decisions, candidates);
        LabelDecision organization = decide(proposal.correspondent(), Facet.ORGANIZATION, vocabulary, text, doubtful,
                decisions, candidates);
        if (organization != null) {
            correspondent = organization.labelId();
        }
        int appliedTopics = 0;
        for (LabelClaim topic : proposal.topics().stream().limit(ClassificationPolicy.MAX_TOPICS).toList()) {
            LabelDecision decision = decide(topic, Facet.TOPIC, vocabulary, text, doubtful, decisions, candidates);
            if (decision != null && decision.apply()) {
                if (appliedTopics >= ClassificationPolicy.MAX_APPLIED_TOPICS) {
                    decisions.put(decision.labelId(), new LabelDecision(decision.labelId(), decision.facet(),
                            decision.confidence(), decision.evidenced(), false));
                } else {
                    appliedTopics++;
                }
            }
        }
        LocalDate reported = parseDate(proposal.documentDate());
        DocumentDate date = ClassificationPolicy.documentDate(reported, proposal.documentDateEvidence(),
                proposal.conflictingDates(), text, LocalDate.now(clock.withZone(settings.zone())));
        List<SubjectClaim> subjects = new ArrayList<>();
        List<MemberId> householdPersons = persons.persons();
        for (SubjectClaim claim : proposal.subjects()) {
            // A member claim must name a person the classifier was shown; any other id is dropped.
            Optional<Subject> subject = subject(claim).filter(s -> s.memberId().map(householdPersons::contains)
                    .orElse(true));
            if (subject.isPresent() && !document.subjects().contains(subject.get())
                    && ClassificationPolicy.offersCandidate(confidence(claim.confidence()),
                            Evidence.quoted(claim.evidence(), text))
                    && subjects.stream().noneMatch(s -> subject(s).equals(subject))) {
                subjects.add(new SubjectClaim(subject.get().kind().name(), subject.get().memberId().orElse(null),
                        confidence(claim.confidence()), clean(claim.evidence(), Evidence.MAX_QUOTE)));
            }
        }
        // The title as it is now: a generated title replaces it only if it is still this one when applied.
        return new Assessment(id, proposal.generation(), proposal.classificationVersion(), document.title(),
                clean(proposal.title(), MAX_TITLE), List.copyOf(decisions.values()), correspondent,
                new DateDecision(date.value() == null ? null : date.value().toString(), date.evidence(),
                        date.status().name()),
                clean(proposal.shortTopic(), DocumentAnalysis.MAX_SHORT_TOPIC),
                flags.stream().map(Enum::name).sorted().toList(), List.copyOf(candidates.values()), subjects,
                placement(proposal.placement(), text));
    }

    /** Resolves one claim to a vocabulary decision, or records an unknown name as a candidate. */
    private static LabelDecision decide(LabelClaim claim, Facet facet, LabelVocabulary vocabulary, String text,
            boolean doubtful, Map<UUID, LabelDecision> decisions, Map<String, CandidateDecision> candidates) {
        if (claim == null) {
            return null;
        }
        Optional<Label> label = Optional.empty();
        if (claim.labelId() != null) {
            label = vocabulary.find(new LabelId(claim.labelId())).map(found -> vocabulary.resolve(found.id()))
                    .filter(found -> found.facet() == facet);
        }
        if (label.isEmpty() && claim.name() != null) {
            label = vocabulary.findByName(facet, claim.name()).map(found -> vocabulary.resolve(found.id()));
        }
        Double confidence = confidence(claim.confidence());
        boolean evidenced = Evidence.quoted(claim.evidence(), text);
        if (label.isPresent()) {
            if (!label.get().acceptsAssignments()) {
                return null;
            }
            UUID id = label.get().id().value();
            LabelDecision decision = new LabelDecision(id, facet.name(), confidence, evidenced,
                    ClassificationPolicy.applies(doubtful, confidence, evidenced));
            LabelDecision previous = decisions.putIfAbsent(id, decision);
            return previous == null ? decision : previous;
        }
        if (facet != Facet.DOCUMENT_TYPE && claim.name() != null
                && ClassificationPolicy.offersCandidate(confidence, evidenced)) {
            try {
                String name = CatalogNames.requireName(claim.name(), "label");
                candidates.putIfAbsent(facet.name() + ":" + CatalogNames.matchKey(name),
                        new CandidateDecision(facet.name(), name, clean(claim.evidence(), Evidence.MAX_QUOTE),
                                confidence));
            } catch (InvalidInputException unusable) {
                // A name that is not a valid label name is dropped.
            }
        }
        return null;
    }

    /**
     * Checks a proposed placement against the document text and the shape rules: usable folder names, at most
     * {@link PlacementTarget#MAX_NEW_FOLDERS} new folders and a quote that is really in the text. Whether it is
     * applied or only suggested is decided when it is applied, against the folder tree of that moment.
     */
    private static PlacementDecision placement(PlacementClaim claim, String text) {
        if (claim == null || claim.folderId() == null && claim.newPath().isEmpty()) {
            return null;
        }
        List<String> path = new ArrayList<>();
        for (String segment : claim.newPath()) {
            String name = clean(segment, CatalogNames.MAX_LENGTH);
            if (name == null) {
                // A path with an unusable segment is not a path; only a named folder can still carry it.
                path = List.of();
                break;
            }
            if (path.size() < PlacementTarget.MAX_NEW_FOLDERS) {
                path.add(name);
            }
        }
        if (claim.folderId() == null && path.isEmpty()) {
            return null;
        }
        return new PlacementDecision(claim.folderId(), claim.anchorFolderId(), path,
                confidence(claim.confidence()), clean(claim.evidence(), Evidence.MAX_QUOTE),
                Evidence.quoted(claim.evidence(), text));
    }

    @Override
    public Applied apply(Actor actor, Assessment assessment, String runRef, String model) {
        access.requireMember(actor);
        lock.shared();
        DocumentId id = assessment.id();
        Document document = load(id);
        DocumentAnalysis analysis = analyses.findById(id).orElseThrow(() -> new StaleVersionException(
                "documentAnalysis", id.toString(), assessment.generation(), 0));
        if (!analysis.accepts(assessment.generation())) {
            throw new StaleVersionException("documentAnalysis", id.toString(), assessment.generation(),
                    analysis.generation());
        }
        List<ProposeClassification.LabelProposal> labelProposals = assessment.labels().stream()
                .map(decision -> new ProposeClassification.LabelProposal(decision.labelId(), decision.confidence(),
                        decision.apply()))
                .toList();
        Set<Flag> flags = EnumSet.noneOf(Flag.class);
        assessment.flags().forEach(flag -> flags.add(Flag.valueOf(flag)));
        // Labels and the folder placement of this run are applied in one transaction on one classification version.
        ProposeClassification.ProposalOutcome labelOutcome = proposals.propose(actor,
                new ProposeClassification.ProposalCommand(id, assessment.classificationVersion(), runRef,
                        labelProposals, placementProposal(assessment, flags, model)));
        String titleBefore = document.title();
        Decision titleDecision = null;
        if (assessment.title() != null && !assessment.title().equals(document.title())) {
            if (!ClassificationPolicy.doubtful(flags)
                    && document.applyGeneratedTitle(assessment.title(), assessment.analyzedTitle())) {
                documents.save(document);
                titleDecision = Decision.APPLIED;
            } else {
                titleDecision = Decision.SUGGESTED;
            }
        }
        Instant now = clock.instant();
        DateDecision date = assessment.documentDate();
        LocalDate dateValue = parseDate(date.value());
        DateStatus dateStatus = DateStatus.valueOf(date.status());
        List<LabelCandidate> candidates = assessment.candidates().stream()
                .map(c -> new LabelCandidate(Facet.valueOf(c.facet()), c.name(), c.evidence(), c.confidence(),
                        Decision.SUGGESTED, null, null, null))
                .toList();
        List<SubjectSuggestion> subjects = new ArrayList<>();
        for (SubjectClaim claim : assessment.subjects()) {
            subject(claim).filter(subject -> !document.subjects().contains(subject)).ifPresent(subject ->
                    subjects.add(new SubjectSuggestion(subject, claim.confidence(), claim.evidence(),
                            Decision.SUGGESTED, null, null)));
        }
        analysis.complete(new AnalysisValues.Outcome(assessment.generation(), runRef, model, now, assessment.title(),
                titleDecision, titleBefore,
                new DocumentDate(dateStatus == DateStatus.UNKNOWN ? null : dateValue, date.evidence(),
                        dateValue == null && dateStatus != DateStatus.CONFLICTING ? DateStatus.UNKNOWN : dateStatus),
                assessment.shortTopic(),
                assessment.correspondentLabelId() == null ? null : new LabelId(assessment.correspondentLabelId()),
                flags, candidates, subjects, labelOutcome.applied(), labelOutcome.suggested()));
        analyses.save(analysis);
        ProposeClassification.PlacementResult placement = labelOutcome.placement();
        boolean moved = placement != null && placement.applied();
        int pending = labelOutcome.suggested() + (titleDecision == Decision.SUGGESTED ? 1 : 0) + candidates.size()
                + subjects.size() + (placement != null && !placement.applied() ? 1 : 0);
        return new Applied(labelOutcome.applied(), labelOutcome.suggested(), titleDecision == Decision.APPLIED,
                moved, pending);
    }

    /** The assessed placement as a proposal for the classification transaction, with the run's provenance. */
    private static ProposeClassification.PlacementProposal placementProposal(Assessment assessment, Set<Flag> flags,
            String model) {
        PlacementDecision decision = assessment.placement();
        if (decision == null) {
            return null;
        }
        return new ProposeClassification.PlacementProposal(decision.folderId(), decision.anchorFolderId(),
                decision.newPath(), decision.confidence(), decision.evidence(), decision.evidenced(),
                ClassificationPolicy.doubtful(flags), assessment.generation(), model);
    }

    // Restore -------------------------------------------------------------------------------------------------------

    @Override
    public ImportDocumentAnalysis.Outcome importAnalysis(Actor actor, DocumentId id, AnalysisRecord record) {
        access.requireMember(actor);
        if (record == null) {
            return ImportDocumentAnalysis.Outcome.NOTHING_TO_APPLY;
        }
        load(id);
        DocumentAnalysis analysis = analyses.findById(id).orElseGet(() -> DocumentAnalysis.none(id));
        if (!analysis.isPristine()) {
            return ImportDocumentAnalysis.Outcome.KEPT_EXISTING;
        }
        DocumentAnalysis.Snapshot snapshot = AnalysisRecords.toSnapshot(record);
        if (snapshot.status() != Status.COMPLETED && snapshot.filenameOverride() == null
                && snapshot.history().isEmpty()) {
            return ImportDocumentAnalysis.Outcome.NOTHING_TO_APPLY;
        }
        analysis.restore(snapshot);
        analyses.save(analysis);
        return ImportDocumentAnalysis.Outcome.APPLIED;
    }

    // Helpers -------------------------------------------------------------------------------------------------------

    private String ocrText(Document document) {
        if (document.state() != ProcessingState.ARCHIVED || document.paperlessDocumentId().isEmpty()) {
            throw new InvalidInputException("document.notArchived", "The document is not archived yet");
        }
        try {
            String text = archive.fetchContent(document.paperlessDocumentId().get());
            return text == null ? "" : text;
        } catch (ArchiveUnavailableException | ArchiveRejectedException unavailable) {
            throw new ContentUnavailableException("the archive text of " + document.id() + " is unavailable",
                    unavailable);
        }
    }

    private static Optional<Subject> subject(SubjectClaim claim) {
        if (claim == null || claim.kind() == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(Subjects.fromViews(List.of(new SubjectView(claim.kind().toUpperCase(Locale.ROOT),
                    claim.memberId()))).iterator().next());
        } catch (InvalidInputException | IllegalArgumentException invalid) {
            return Optional.empty();
        }
    }

    private static Double confidence(Double value) {
        return value == null || value.isNaN() || value < 0 || value > 1 ? null : value;
    }

    private static LocalDate parseDate(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(value.strip());
        } catch (DateTimeParseException invalid) {
            return null;
        }
    }

    /** Trimmed single-line text without control or format characters, cut to {@code max} code points. */
    static String clean(String value, int max) {
        if (value == null) {
            return null;
        }
        String text = WHITESPACE.matcher(INVISIBLE.matcher(value).replaceAll(" ")).replaceAll(" ").strip();
        if (text.codePointCount(0, text.length()) > max) {
            text = text.substring(0, text.offsetByCodePoints(0, max)).strip();
        }
        return text.isEmpty() ? null : text;
    }

    private DocumentAnalysis loadVersion(DocumentId id, long expectedVersion) {
        DocumentAnalysis analysis = analyses.findById(id).orElseGet(() -> DocumentAnalysis.none(id));
        if (analysis.version() != expectedVersion) {
            throw new StaleVersionException("documentAnalysis", id.toString(), expectedVersion, analysis.version());
        }
        return analysis;
    }

    private Document load(DocumentId id) {
        return documents.findById(id).orElseThrow(() -> new NotFoundException("document", id.toString()));
    }

    /** A document a member may change: it exists and is not in the trash. */
    private Document loadActive(DocumentId id) {
        Document document = load(id);
        document.requireNotTrashed();
        return document;
    }
}
