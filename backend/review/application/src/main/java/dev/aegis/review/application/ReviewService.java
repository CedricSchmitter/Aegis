package dev.aegis.review.application;

import dev.aegis.identity.application.api.MemberDirectory;
import dev.aegis.identity.application.api.MemberSummary;
import dev.aegis.notifications.application.api.NoticeDraft;
import dev.aegis.notifications.application.api.PublishNotice;
import dev.aegis.review.application.api.ReviewInbox;
import dev.aegis.review.application.api.ReviewPortability;
import dev.aegis.review.application.api.ReviewReconciliation;
import dev.aegis.review.application.ports.ReconciliationLock;
import dev.aegis.review.application.ports.ReviewActionDispatcher;
import dev.aegis.review.application.ports.ReviewProducer;
import dev.aegis.review.domain.ReviewItem;
import dev.aegis.review.domain.ReviewItem.Candidate;
import dev.aegis.review.domain.ReviewRepository;
import dev.aegis.review.domain.ReviewStatus;
import dev.aegis.shared.Actor;
import dev.aegis.shared.HouseholdAccess;
import dev.aegis.shared.InvalidInputException;
import dev.aegis.shared.MemberId;
import dev.aegis.shared.NotAuthorizedException;
import dev.aegis.shared.NotFoundException;
import dev.aegis.shared.StaleVersionException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Reconciles persisted workflow cards with authoritative producer views and delegates every business action.
 * Reconciliation runs when a member opens the inbox and in the background as {@link ReviewReconciliation#RECONCILER}
 * (ADR-0009); only members dismiss or act on cards.
 */
public class ReviewService implements ReviewInbox, ReviewReconciliation, ReviewPortability {
    private final ReviewRepository repository;
    private final List<ReviewProducer> producers;
    private final ReviewActionDispatcher actions;
    private final ReconciliationLock lock;
    private final HouseholdAccess access;
    private final MemberDirectory members;
    private final PublishNotice notices;
    private final Clock clock;

    public ReviewService(ReviewRepository repository, List<ReviewProducer> producers, ReviewActionDispatcher actions,
            ReconciliationLock lock, HouseholdAccess access, MemberDirectory members, PublishNotice notices,
            Clock clock) {
        this.repository = repository;
        this.producers = List.copyOf(producers);
        this.actions = actions;
        this.lock = lock;
        this.access = access;
        this.members = members;
        this.notices = notices;
        this.clock = clock;
    }

    @Override
    public List<Item> list(Actor actor, String status, String category) {
        access.requireMember(actor);
        reconcileAs(actor);
        return repository.findAll().stream()
                .filter(i -> status == null || status.isBlank() || i.status().name().equals(status))
                .filter(i -> category == null || category.isBlank() || i.category().equals(category))
                .sorted(Comparator.comparing(ReviewItem::createdAt).reversed()).map(ReviewService::view).toList();
    }

    @Override
    public Report reconcile(Actor actor) {
        if (actor != null && actor.isHouseholdProcess()) {
            if (!RECONCILER.equals(actor)) {
                throw new NotAuthorizedException("household process " + actor.process()
                        + " may not reconcile the review inbox");
            }
            access.requireReader(actor);
        } else {
            access.requireMember(actor);
        }
        return reconcileAs(actor);
    }

    @Override
    public Item dismiss(Actor actor, UUID id, long expectedVersion) {
        access.requireMember(actor);
        ReviewItem item = load(id);
        check(item, expectedVersion);
        long old = item.version();
        item.decide(ReviewStatus.DISMISSED, actor.memberId(), clock.instant());
        repository.update(item, old);
        return view(item);
    }

    @Override
    public Item act(Actor actor, UUID id, String action, long expectedVersion) {
        access.requireMember(actor);
        ReviewItem item = load(id);
        check(item, expectedVersion);
        if (!item.actions().contains(action)) {
            throw new InvalidInputException("review.action.invalid", "Action is not available for this review item");
        }
        actions.execute(actor, item, action);
        long old = item.version();
        item.decide(ReviewStatus.RESOLVED, actor.memberId(), clock.instant());
        repository.update(item, old);
        return view(item);
    }

    /**
     * One reconciliation, serialized with every other one by the lock. Producers are read as the given actor, so
     * their own authorization applies. A new candidate is inserted if absent, and only the insert that created the
     * card publishes its notices, in the same transaction. An open card that no producer reports any more is
     * superseded, unless a member decided it meanwhile.
     */
    private Report reconcileAs(Actor actor) {
        lock.exclusive();
        Map<String, Candidate> current = new LinkedHashMap<>();
        for (ReviewProducer producer : producers) {
            for (Candidate candidate : producer.findCurrent(actor)) {
                current.putIfAbsent(candidate.fingerprint(), candidate);
            }
        }
        Set<String> existing = current.isEmpty() ? Set.of() : repository.findExistingFingerprints(current.keySet());
        List<MemberSummary> recipients = null;
        int created = 0;
        for (Candidate candidate : current.values()) {
            if (existing.contains(candidate.fingerprint())) {
                continue;
            }
            ReviewItem item = ReviewItem.create(cardId(candidate.fingerprint()), candidate, clock.instant());
            if (!repository.insertIfAbsent(item)) {
                continue;
            }
            created++;
            if (recipients == null) {
                recipients = members.listMembers(actor).stream().filter(MemberSummary::active).toList();
            }
            Map<String, String> noticeParams = noticeParams(item);
            for (MemberSummary member : recipients) {
                notices.publish(new NoticeDraft(member.id(), "REVIEW_ITEM", "review:" + item.id(), noticeParams,
                        "REVIEW", item.id().toString()));
            }
        }
        int superseded = 0;
        for (ReviewItem item : repository.findOpen()) {
            if (!current.containsKey(item.fingerprint()) && supersede(item)) {
                superseded++;
            }
        }
        return new Report(current.size(), created, superseded);
    }

    /** False when a member dismissed or resolved the card after it was read; that decision stands. */
    private boolean supersede(ReviewItem item) {
        long expected = item.version();
        item.supersede(clock.instant());
        try {
            repository.update(item, expected);
            return true;
        } catch (StaleVersionException decided) {
            return false;
        }
    }

    /**
     * A notice names its card the way the inbox does: the card's presentation key and its parameters, such as the
     * document title in its original language (issue #91). Only stable keys and source values are stored; the UI
     * localizes them. {@code titleKey} is set last so a card parameter can never replace it.
     */
    private static Map<String, String> noticeParams(ReviewItem item) {
        Map<String, String> params = new LinkedHashMap<>(item.params());
        params.put("titleKey", item.titleKey());
        return params;
    }

    private static UUID cardId(String fingerprint) {
        return UUID.nameUUIDFromBytes(fingerprint.getBytes(StandardCharsets.UTF_8));
    }

    private ReviewItem load(UUID id) {
        return repository.find(id).orElseThrow(() -> new NotFoundException("reviewItem", id.toString()));
    }

    private static void check(ReviewItem item, long expectedVersion) {
        if (item.version() != expectedVersion) {
            throw new StaleVersionException("reviewItem", item.id().toString(), expectedVersion, item.version());
        }
        if (item.status() != ReviewStatus.OPEN) {
            throw new InvalidInputException("review.item.closed", "Review item is no longer open");
        }
    }

    private static Item view(ReviewItem i) {
        return new Item(i.id(), i.sourceKind(), i.sourceId(), i.sourceVersion(), i.category(), i.reason(),
                i.priority(), i.titleKey(), i.params(), i.linkType(), i.linkId(), i.actions(), i.status().name(),
                i.createdAt(), i.updatedAt(), i.version());
    }

    @Override
    public List<Record> exportAll(Actor actor) {
        access.requireMember(actor);
        return repository.findAll().stream().map(i -> new Record(i.id(), i.fingerprint(), i.producer(),
                i.sourceKind(), i.sourceId(), i.sourceVersion(), i.category(), i.reason(), i.priority(),
                i.titleKey(), i.params(), i.linkType(), i.linkId(), i.actions(), i.status().name(), i.createdAt(),
                i.updatedAt(), i.version(), i.decidedBy() == null ? null : i.decidedBy().toString(),
                i.decidedAt())).toList();
    }

    /** Insert-only by stable id and fingerprint, so a restore never overwrites a newer local decision. */
    @Override
    public void importAll(Actor actor, List<Record> records) {
        access.requireMember(actor);
        for (Record r : records) {
            repository.insertIfAbsent(ReviewItem.rehydrate(r.id(), r.fingerprint(), r.producer(), r.sourceKind(),
                    r.sourceId(), r.sourceVersion(), r.category(), r.reason(), r.priority(), r.titleKey(),
                    r.params(), r.linkType(), r.linkId(), r.actions(), ReviewStatus.valueOf(r.status()),
                    r.createdAt(), r.updatedAt(), r.version(),
                    r.decidedBy() == null ? null : MemberId.of(r.decidedBy()), r.decidedAt()));
        }
    }
}
