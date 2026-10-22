package dev.aegis.review.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.aegis.identity.application.api.MemberDirectory;
import dev.aegis.identity.application.api.MemberSummary;
import dev.aegis.notifications.application.api.NoticeDraft;
import dev.aegis.notifications.application.api.PublishNotice;
import dev.aegis.review.application.api.ReviewReconciliation;
import dev.aegis.review.application.api.ReviewReconciliation.Report;
import dev.aegis.review.application.ports.ReviewProducer;
import dev.aegis.review.domain.ReviewItem;
import dev.aegis.review.domain.ReviewRepository;
import dev.aegis.shared.Actor;
import dev.aegis.shared.HouseholdAccess;
import dev.aegis.shared.MemberId;
import dev.aegis.shared.NotAuthorizedException;
import dev.aegis.shared.ProductLocale;
import dev.aegis.shared.StaleVersionException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ReviewServiceTest {
    private static final MemberId LISA = MemberId.random();
    private static final MemberId CEDRIC = MemberId.random();
    private static final MemberId FORMER = MemberId.random();
    private static final Actor ACTOR = new Actor(LISA);
    private static final Actor RECONCILER = ReviewReconciliation.RECONCILER;

    private final MemoryRepository repo = new MemoryRepository();
    private final MutableProducer producer = new MutableProducer();
    private final List<NoticeDraft> notices = new ArrayList<>();
    private final List<String> actions = new ArrayList<>();
    private final List<String> events = new ArrayList<>();
    private final ReviewService service = new ReviewService(repo, List.of(producer),
            (actor, item, action) -> actions.add(action + ":" + item.id()), () -> events.add("lock"),
            new HouseholdPolicy(), directory(), draft -> {
                notices.add(draft);
                return PublishNotice.PublishOutcome.PUBLISHED;
            }, Clock.fixed(Instant.parse("2026-09-21T10:00:00Z"), ZoneOffset.UTC));

    @Test
    void reconciliationDeduplicatesAndNotifiesBothMembersOnce() {
        producer.items = List.of(candidate(1));
        assertThat(service.list(ACTOR, "OPEN", null)).hasSize(1);
        assertThat(service.list(ACTOR, "OPEN", null)).hasSize(1);
        assertThat(notices).hasSize(2).extracting(NoticeDraft::linkType).containsOnly("REVIEW");
    }

    @Test
    void backgroundReconciliationCreatesTheCardAndNotifiesEveryActiveMemberWithoutAnInboxVisit() {
        producer.items = List.of(candidate(1));

        assertThat(service.reconcile(RECONCILER)).isEqualTo(new Report(1, 1, 0));

        assertThat(producer.readBy).containsExactly(RECONCILER);
        ReviewItem card = repo.findByFingerprint("doc:1:1").orElseThrow();
        assertThat(notices).extracting(NoticeDraft::recipient).containsExactlyInAnyOrder(LISA, CEDRIC);
        assertThat(notices).allSatisfy(notice -> {
            assertThat(notice.kind()).isEqualTo("REVIEW_ITEM");
            assertThat(notice.dedupKey()).isEqualTo("review:" + card.id());
            assertThat(notice.linkId()).isEqualTo(card.id().toString());
        });

        // A repeated run, as after a restart, creates nothing twice; a member opening the inbox neither.
        assertThat(service.reconcile(RECONCILER)).isEqualTo(new Report(1, 0, 0));
        assertThat(service.list(ACTOR, "OPEN", null)).singleElement()
                .satisfies(item -> assertThat(item.id()).isEqualTo(card.id()));
        assertThat(notices).hasSize(2);
    }

    @Test
    void theNoticeNamesItsCardWithTheCardsKeyAndParametersInsteadOfTranslatedText() {
        producer.items = List.of(new ReviewItem.Candidate("no-text:7:42", "documents", "DOCUMENT", "7", 3,
                "PROCESSING_FAILURE", "NO_TEXT_RECOGNIZED", "NORMAL", "review.noText",
                Map.of("title", "Brief der Stadtwerke", "titleKey", "review.spoofed"), "DOCUMENT", "7", List.of()));

        service.reconcile(RECONCILER);

        ReviewItem card = repo.findByFingerprint("no-text:7:42").orElseThrow();
        assertThat(notices).hasSize(2).allSatisfy(notice -> {
            // The source title stays in its original language; the card's key wins over a colliding parameter.
            assertThat(notice.params()).containsExactlyInAnyOrderEntriesOf(
                    Map.of("titleKey", "review.noText", "title", "Brief der Stadtwerke"));
            assertThat(notice.dedupKey()).isEqualTo("review:" + card.id());
        });
    }

    @Test
    void theReconcilerMayOnlyReconcile() {
        producer.items = List.of(candidate(1));
        service.reconcile(RECONCILER);
        ReviewItem card = repo.findByFingerprint("doc:1:1").orElseThrow();

        assertThatThrownBy(() -> service.dismiss(RECONCILER, card.id(), card.version()))
                .isInstanceOf(NotAuthorizedException.class);
        assertThatThrownBy(() -> service.act(RECONCILER, card.id(), "RETRY", card.version()))
                .isInstanceOf(NotAuthorizedException.class);
        assertThatThrownBy(() -> service.list(RECONCILER, null, null)).isInstanceOf(NotAuthorizedException.class);
        // The export query only reads and is open to household processes for the daily export (ADR-0029).
        assertThat(service.exportAll(RECONCILER)).hasSize(1);
        assertThatThrownBy(() -> service.reconcile(Actor.householdProcess("portable-export")))
                .isInstanceOf(NotAuthorizedException.class);
        assertThatThrownBy(() -> service.reconcile(new Actor(MemberId.random())))
                .isInstanceOf(NotAuthorizedException.class);
        assertThat(actions).isEmpty();
        assertThat(repo.findByFingerprint("doc:1:1").orElseThrow().status().name()).isEqualTo("OPEN");
    }

    @Test
    void everyReconciliationTakesTheLockBeforeReadingTheProducers() {
        producer.items = List.of(candidate(1));
        service.reconcile(RECONCILER);
        service.list(ACTOR, null, null);
        assertThat(events).containsExactly("lock", "read", "lock", "read");
    }

    @Test
    void onlyTheReconciliationThatInsertsACardPublishesItsNotices() {
        producer.items = List.of(candidate(1));
        // Another reconciliation inserts the card after this one found it missing.
        repo.hideFromExistenceCheck = true;
        repo.insertIfAbsent(ReviewItem.create(UUID.randomUUID(), candidate(1), Instant.EPOCH));

        assertThat(service.reconcile(RECONCILER)).isEqualTo(new Report(1, 0, 0));
        assertThat(notices).isEmpty();
        assertThat(repo.findAll()).hasSize(1);
    }

    @Test
    void anOpenCardThatNoProducerReportsIsSupersededWithoutNamingAMember() {
        producer.items = List.of(candidate(1));
        service.list(ACTOR, "OPEN", null);
        producer.items = List.of();

        assertThat(service.reconcile(RECONCILER)).isEqualTo(new Report(0, 0, 1));

        ReviewItem card = repo.findByFingerprint("doc:1:1").orElseThrow();
        assertThat(card.status().name()).isEqualTo("SUPERSEDED");
        assertThat(card.decidedBy()).isNull();
        assertThat(card.decidedAt()).isEqualTo(Instant.parse("2026-09-21T10:00:00Z"));
    }

    @Test
    void aMemberDecisionMadeDuringReconciliationStands() {
        producer.items = List.of(candidate(1));
        var item = service.list(ACTOR, "OPEN", null).getFirst();
        producer.items = List.of();
        // Reconciliation read the card while it was open; Lisa dismisses it before the supersede is written.
        repo.staleOpen = List.of(repo.findByFingerprint("doc:1:1").orElseThrow());
        service.dismiss(ACTOR, item.id(), item.version());

        assertThat(service.reconcile(RECONCILER)).isEqualTo(new Report(0, 0, 0));
        ReviewItem card = repo.findByFingerprint("doc:1:1").orElseThrow();
        assertThat(card.status().name()).isEqualTo("DISMISSED");
        assertThat(card.decidedBy()).isEqualTo(LISA);
    }

    @Test
    void dismissalSurvivesAnUnchangedProducerAndNewEvidenceSupersedesIt() {
        producer.items = List.of(candidate(1));
        var item = service.list(ACTOR, "OPEN", null).getFirst();
        service.dismiss(ACTOR, item.id(), item.version());
        assertThat(service.list(ACTOR, "DISMISSED", null)).hasSize(1);
        producer.items = List.of(candidate(2));
        assertThat(service.list(ACTOR, "OPEN", null)).singleElement()
                .satisfies(next -> assertThat(next.sourceVersion()).isEqualTo(2));
        assertThat(service.list(ACTOR, "SUPERSEDED", null)).isEmpty();
    }

    @Test
    void actionIsVersionCheckedAndDelegated() {
        producer.items = List.of(candidate(1));
        var item = service.list(ACTOR, "OPEN", null).getFirst();
        service.act(ACTOR, item.id(), "RETRY", item.version());
        assertThat(actions).containsExactly("RETRY:" + item.id());
        assertThatThrownBy(() -> service.dismiss(ACTOR, item.id(), item.version()))
                .isInstanceOf(StaleVersionException.class);
    }

    @Test
    void theOpenCountForTheInboxBadgeCountsOnlyOpenCardsAndDoesNotReconcile() {
        producer.items = List.of(candidate(1), new ReviewItem.Candidate("doc:2:1", "documents", "DOCUMENT", "2", 1,
                "PROCESSING_FAILURE", "FAILED", "HIGH", "review.failed", Map.of("title", "Letter"), "DOCUMENT", "2",
                List.of()));
        var first = service.list(ACTOR, "OPEN", null).getFirst();
        service.dismiss(ACTOR, first.id(), first.version());
        events.clear();
        // A new candidate appears, but only opening the inbox or the daily job turns it into a card.
        producer.items = List.of(candidate(1), candidate(3));

        assertThat(service.openCount(ACTOR)).isEqualTo(1);
        assertThat(events).isEmpty();
        assertThatThrownBy(() -> service.openCount(RECONCILER)).isInstanceOf(NotAuthorizedException.class);
    }

    @Test
    void outsidersCannotReadOrAct() {
        assertThatThrownBy(() -> service.list(new Actor(MemberId.random()), null, null))
                .isInstanceOf(NotAuthorizedException.class);
        assertThatThrownBy(() -> service.openCount(new Actor(MemberId.random())))
                .isInstanceOf(NotAuthorizedException.class);
    }

    @Test
    void exportAndIdempotentRestorePreserveLifecycleAndIdentity() {
        producer.items = List.of(candidate(1));
        var item = service.list(ACTOR, "OPEN", null).getFirst();
        service.dismiss(ACTOR, item.id(), item.version());
        var exported = service.exportAll(ACTOR);
        MemoryRepository restored = new MemoryRepository();
        ReviewService target = new ReviewService(restored, List.of(), (a, i, x) -> {
        }, () -> {
        }, new HouseholdPolicy(), directory(), d -> PublishNotice.PublishOutcome.PUBLISHED, Clock.systemUTC());
        target.importAll(ACTOR, exported);
        target.importAll(ACTOR, exported);
        assertThat(target.exportAll(ACTOR)).singleElement().satisfies(r -> {
            assertThat(r.id()).isEqualTo(item.id());
            assertThat(r.status()).isEqualTo("DISMISSED");
            assertThat(r.sourceVersion()).isEqualTo(1);
            assertThat(r.decidedBy()).isEqualTo(LISA.toString());
        });
    }

    private static ReviewItem.Candidate candidate(long version) {
        return new ReviewItem.Candidate("doc:1:" + version, "documents", "DOCUMENT", "1", version,
                "PROCESSING_FAILURE", "FAILED", "HIGH", "review.failed", Map.of("title", "Bill"), "DOCUMENT", "1",
                List.of("RETRY"));
    }

    private static MemberDirectory directory() {
        var members = List.of(new MemberSummary(LISA, "lisa", "Lisa", ProductLocale.DE, true),
                new MemberSummary(CEDRIC, "cedric", "Cedric", ProductLocale.EN, true),
                new MemberSummary(FORMER, "former", "Former", ProductLocale.DE, false));
        return new MemberDirectory() {
            public List<MemberSummary> listMembers(Actor actor) {
                new HouseholdPolicy().requireReader(actor);
                return members;
            }

            public Optional<MemberSummary> findMember(MemberId id) {
                return members.stream().filter(m -> m.id().equals(id)).findFirst();
            }
        };
    }

    /** Mirrors the identity context's policy: Lisa and Cedric are members; household processes may only read. */
    private static final class HouseholdPolicy implements HouseholdAccess {
        @Override
        public void requireMember(Actor actor) {
            if (actor == null || actor.isHouseholdProcess() || !Set.of(LISA, CEDRIC).contains(actor.memberId())) {
                throw new NotAuthorizedException("member required");
            }
        }

        @Override
        public void requireReader(Actor actor) {
            if (actor == null || !actor.isHouseholdProcess()) {
                requireMember(actor);
            }
        }
    }

    private final class MutableProducer implements ReviewProducer {
        List<ReviewItem.Candidate> items = List.of();
        final List<Actor> readBy = new ArrayList<>();

        public String id() {
            return "documents";
        }

        public List<ReviewItem.Candidate> findCurrent(Actor actor) {
            events.add("read");
            readBy.add(actor);
            return items;
        }
    }

    /** Keeps copies, like a database, so a stale write is detected by its version. */
    private static final class MemoryRepository implements ReviewRepository {
        final Map<UUID, ReviewItem> all = new LinkedHashMap<>();
        boolean hideFromExistenceCheck;
        List<ReviewItem> staleOpen;

        public Optional<ReviewItem> find(UUID id) {
            return Optional.ofNullable(all.get(id)).map(MemoryRepository::copy);
        }

        public Optional<ReviewItem> findByFingerprint(String f) {
            return all.values().stream().filter(i -> i.fingerprint().equals(f)).findFirst().map(MemoryRepository::copy);
        }

        public List<ReviewItem> findAll() {
            return all.values().stream().map(MemoryRepository::copy).toList();
        }

        public List<ReviewItem> findOpen() {
            if (staleOpen != null) {
                return staleOpen;
            }
            return all.values().stream().filter(i -> i.status().name().equals("OPEN")).map(MemoryRepository::copy)
                    .toList();
        }

        public long countOpen() {
            return all.values().stream().filter(i -> i.status().name().equals("OPEN")).count();
        }

        public List<UUID> deleteByRecord(String kind, String id) {
            List<UUID> deleted = all.values().stream()
                    .filter(i -> (i.sourceKind().equals(kind) && i.sourceId().equals(id))
                            || (i.linkType().equals(kind) && i.linkId().equals(id)))
                    .map(ReviewItem::id).toList();
            deleted.forEach(all::remove);
            return deleted;
        }

        public Set<String> findExistingFingerprints(Collection<String> fingerprints) {
            Set<String> existing = new HashSet<>();
            if (!hideFromExistenceCheck) {
                all.values().stream().map(ReviewItem::fingerprint).filter(fingerprints::contains)
                        .forEach(existing::add);
            }
            return existing;
        }

        public boolean insertIfAbsent(ReviewItem i) {
            if (all.containsKey(i.id()) || all.values().stream().anyMatch(o -> o.fingerprint().equals(i.fingerprint()))) {
                return false;
            }
            all.put(i.id(), copy(i));
            return true;
        }

        public void update(ReviewItem i, long expected) {
            long actual = all.get(i.id()).version();
            if (actual != expected) {
                throw new StaleVersionException("reviewItem", i.id().toString(), expected, actual);
            }
            all.put(i.id(), copy(i));
        }

        static ReviewItem copy(ReviewItem i) {
            return ReviewItem.rehydrate(i.id(), i.fingerprint(), i.producer(), i.sourceKind(), i.sourceId(),
                    i.sourceVersion(), i.category(), i.reason(), i.priority(), i.titleKey(), i.params(), i.linkType(),
                    i.linkId(), i.actions(), i.status(), i.createdAt(), i.updatedAt(), i.version(), i.decidedBy(),
                    i.decidedAt());
        }
    }
}
