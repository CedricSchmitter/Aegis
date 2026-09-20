package dev.aegis.contracts.application;

import static dev.aegis.contracts.application.ContractTestSupport.CEDRIC;
import static dev.aegis.contracts.application.ContractTestSupport.LISA;
import static dev.aegis.contracts.application.ContractTestSupport.STRANGER;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.aegis.contracts.application.ContractTestSupport.KnownDocuments;
import dev.aegis.contracts.application.api.ContractCommands.CorrectFactCommand;
import dev.aegis.contracts.application.api.ContractCommands.CreateContractCommand;
import dev.aegis.contracts.application.api.ContractCommands.EnterFactCommand;
import dev.aegis.contracts.application.api.ContractDeadlineQuery.ContractDeadlineView;
import dev.aegis.contracts.application.api.ContractViews.ContractView;
import dev.aegis.contracts.application.api.ContractViews.FactView;
import dev.aegis.contracts.application.api.ImportContract;
import dev.aegis.contracts.application.api.RecordContractObservation.ObservationCommand;
import dev.aegis.contracts.application.api.RecordContractObservation.ObservationResult;
import dev.aegis.shared.ContractId;
import dev.aegis.shared.DocumentId;
import dev.aegis.shared.InvalidInputException;
import dev.aegis.shared.NotAuthorizedException;
import dev.aegis.shared.NotFoundException;
import dev.aegis.shared.StaleVersionException;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The manual path that makes the feature work without AI, and the public contract issue #63 consumes: entering,
 * confirming and correcting facts, and reading a deadline only when confirmed rules are sufficient.
 */
class ContractServiceTest {
    private static final LocalDate TODAY = LocalDate.of(2026, 9, 20);

    private final InMemoryContractRepository repository = new InMemoryContractRepository();
    private final KnownDocuments documents = new KnownDocuments();
    private final Clock clock = ContractTestSupport.clockAt("2026-09-20T08:00:00Z");
    private ContractService commands;
    private ContractQueryService queries;
    private DocumentId contractPdf;

    @BeforeEach
    void setUp() {
        ContractPresenter presenter = new ContractPresenter(documents);
        commands = new ContractService(repository, documents, presenter, ContractTestSupport.household(), clock);
        queries = new ContractQueryService(repository, presenter, ContractTestSupport.household(), clock);
        contractPdf = documents.add("Fitnessstudio Vertrag");
    }

    @Test
    void onlyHouseholdMembersReachContractFacts() {
        assertThatThrownBy(() -> commands.create(STRANGER, new CreateContractCommand("Fremd", null, null)))
                .isInstanceOf(NotAuthorizedException.class);
        assertThatThrownBy(() -> queries.list(STRANGER)).isInstanceOf(NotAuthorizedException.class);
        assertThatThrownBy(() -> queries.confirmedDeadlines(STRANGER, TODAY))
                .isInstanceOf(NotAuthorizedException.class);
    }

    @Test
    void aMemberEntersFactsAndTheDeadlineFollowsFromTheConfirmedRules() {
        ContractView created = commands.create(LISA,
                new CreateContractCommand("Fitnessstudio", "McFit GmbH", contractPdf));
        assertThat(created.counterparty()).isEqualTo("McFit GmbH");
        assertThat(created.deadline().determined()).isFalse();
        assertThat(created.deadline().missing()).contains("CANCELLATION_DEADLINE", "END_DATE", "NOTICE_PERIOD");

        ContractView withEnd = enter(created.id(), created.version(), "END_DATE", "2028-01-01");
        ContractView withNotice = enter(withEnd.id(), withEnd.version(), "NOTICE_PERIOD", "P3M");

        assertThat(withNotice.deadline().determined()).isTrue();
        assertThat(withNotice.deadline().deadline()).isEqualTo(LocalDate.of(2027, 10, 1));
        assertThat(withNotice.deadline().basis()).isEqualTo("NOTICE_PERIOD_BEFORE_END");
        assertThat(withNotice.facts()).allSatisfy(fact -> assertThat(fact.reviewState()).isEqualTo("CONFIRMED"));
        assertThat(withNotice.sources()).singleElement()
                .extracting(source -> source.documentTitle()).isEqualTo("Fitnessstudio Vertrag");
    }

    @Test
    void theOwnerExampleGivesTheDeadlineAndItsSourceToTheConsumingContext() {
        ContractView contract = commands.create(LISA,
                new CreateContractCommand("Fitnessstudio", "McFit GmbH", contractPdf));
        contract = enter(contract.id(), contract.version(), "END_DATE", "2028-01-01");
        contract = enter(contract.id(), contract.version(), "CANCELLATION_DEADLINE", "2027-11-30");

        List<ContractDeadlineView> deadlines = queries.confirmedDeadlines(LISA, TODAY);

        assertThat(deadlines).singleElement().satisfies(view -> {
            assertThat(view.contractId()).isEqualTo(contract().id());
            assertThat(view.name()).isEqualTo("Fitnessstudio");
            assertThat(view.counterparty()).isEqualTo("McFit GmbH");
            assertThat(view.contractEnd()).isEqualTo(LocalDate.of(2028, 1, 1));
            assertThat(view.cancellationDeadline()).isEqualTo(LocalDate.of(2027, 11, 30));
            assertThat(view.basis()).isEqualTo("STATED_DEADLINE");
            assertThat(view.sourceDocumentId()).isEqualTo(contractPdf);
            assertThat(view.status()).isEqualTo("ACTIVE");
            assertThat(view.contractVersion()).isGreaterThan(0);
        });
        assertThat(queries.confirmedDeadline(LISA, contract().id(), TODAY)).isPresent();
    }

    @Test
    void anExtractedDeadlineIsNoDeadlineUntilAMemberConfirmsIt() {
        ContractView contract = commands.create(LISA, new CreateContractCommand("Fitnessstudio", null, null));
        ObservationResult proposal = commands.observe(LISA, contract.id(), new ObservationCommand(
                "CANCELLATION_DEADLINE", "2027-11-30", null, null, contractPdf, 2,
                "Kündigung bis 30.11.2027", 0.92, "run-42"));

        assertThat(proposal.reviewState()).isEqualTo("EXTRACTED");
        assertThat(queries.confirmedDeadlines(LISA, TODAY)).isEmpty();

        ContractView reviewed = queries.get(LISA, contract.id());
        commands.confirmFact(CEDRIC, contract.id(), proposal.factId(), reviewed.version());

        assertThat(queries.confirmedDeadlines(LISA, TODAY)).singleElement()
                .extracting(ContractDeadlineView::cancellationDeadline).isEqualTo(LocalDate.of(2027, 11, 30));
        FactView confirmed = fact(queries.get(LISA, contract.id()), proposal.factId());
        assertThat(confirmed.decidedBy()).isEqualTo(CEDRIC.memberId());
        assertThat(confirmed.source().page()).isEqualTo(2);
        assertThat(confirmed.confidence()).isEqualTo(0.92);
    }

    @Test
    void aContradictingInvoiceNeverOverwritesAConfirmedAmount() {
        DocumentId invoice = documents.add("Rechnung 2027");
        ContractView contract = commands.create(LISA, new CreateContractCommand("Fitnessstudio", null, null));
        contract = enter(contract.id(), contract.version(), "AMOUNT", "29.90 EUR");

        ObservationResult observation = commands.observe(LISA, contract.id(), new ObservationCommand("AMOUNT",
                "39.90 EUR", null, null, invoice, null, null, 0.88, "run-43"));

        assertThat(observation.outcome()).isEqualTo("CONTRADICTS_CONFIRMED");
        ContractView reviewed = queries.get(LISA, contract.id());
        assertThat(reviewed.conflicts()).singleElement()
                .extracting(conflict -> conflict.kind()).isEqualTo("EXTRACTED_AGAINST_CONFIRMED");
        assertThat(effectiveValue(reviewed, "AMOUNT")).isEqualTo("29.90 EUR");
    }

    @Test
    void aCorrectionKeepsTheReplacedObservationAsHistory() {
        ContractView contract = commands.create(LISA, new CreateContractCommand("Fitnessstudio", null, null));
        contract = enter(contract.id(), contract.version(), "END_DATE", "2028-01-10");
        UUID wrong = contract.facts().get(0).id();

        ContractView corrected = commands.correctFact(CEDRIC, contract.id(), wrong,
                new CorrectFactCommand("2028-01-01", null, null, "Zahlendreher"), contract.version());

        assertThat(fact(corrected, wrong).reviewState()).isEqualTo("SUPERSEDED");
        assertThat(fact(corrected, wrong).supersededBy()).isNotNull();
        assertThat(corrected.facts()).hasSize(2);
        assertThat(effectiveValue(corrected, "END_DATE")).isEqualTo("2028-01-01");
    }

    @Test
    void anOutdatedViewIsRejectedInsteadOfOverwritingTheOtherMemberDecision() {
        ContractView contract = commands.create(LISA, new CreateContractCommand("Fitnessstudio", null, null));
        long staleVersion = contract.version();
        enter(contract.id(), staleVersion, "END_DATE", "2028-01-01");

        assertThatThrownBy(() -> enter(contract.id(), staleVersion, "NOTICE_PERIOD", "P3M"))
                .isInstanceOf(StaleVersionException.class);
    }

    @Test
    void factsRejectUnknownTypesValuesAndDocuments() {
        ContractView contract = commands.create(LISA, new CreateContractCommand("Fitnessstudio", null, null));
        ContractId id = contract.id();
        long version = contract.version();

        assertThatThrownBy(() -> enter(id, version, "COLOUR", "blau"))
                .isInstanceOf(InvalidInputException.class)
                .hasFieldOrPropertyWithValue("code", "contract.fact.type.unknown");
        assertThatThrownBy(() -> enter(id, version, "END_DATE", "bald"))
                .isInstanceOf(InvalidInputException.class)
                .hasFieldOrPropertyWithValue("code", "contract.fact.value.invalid");
        assertThatThrownBy(() -> commands.enterFact(LISA, id, new EnterFactCommand("END_DATE", "2028-01-01", null,
                null, DocumentId.random(), null, null, null), version)).isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> commands.enterFact(LISA, id, new EnterFactCommand("END_DATE", "2028-01-01", null,
                null, null, 3, null, null), version)).isInstanceOf(InvalidInputException.class)
                .hasFieldOrPropertyWithValue("code", "contract.source.missing");
        assertThatThrownBy(() -> commands.observe(LISA, id, new ObservationCommand("END_DATE", "2028-01-01", null,
                null, null, null, null, 0.5, "run-1"))).isInstanceOf(InvalidInputException.class)
                .hasFieldOrPropertyWithValue("code", "contract.source.missing");
    }

    @Test
    void contractsAreFoundThroughTheDocumentTheirFactsCameFrom() {
        ContractView contract = commands.create(LISA,
                new CreateContractCommand("Fitnessstudio", "McFit GmbH", contractPdf));

        assertThat(queries.forDocument(LISA, contractPdf)).singleElement()
                .extracting(summary -> summary.id()).isEqualTo(contract.id());
        assertThat(queries.forDocument(LISA, documents.add("Anderes"))).isEmpty();
    }

    @Test
    void anExportedContractRestoresWithItsFactIdsStatesAndRelationships() {
        ContractView contract = commands.create(LISA,
                new CreateContractCommand("Fitnessstudio", "McFit GmbH", contractPdf));
        contract = enter(contract.id(), contract.version(), "END_DATE", "2028-01-10");
        UUID wrong = fact(contract, "END_DATE").id();
        contract = commands.correctFact(CEDRIC, contract.id(), wrong,
                new CorrectFactCommand("2028-01-01", null, null, "Zahlendreher"), contract.version());
        contract = enter(contract.id(), contract.version(), "NOTICE_PERIOD", "P3M");
        var exported = queries.exportAll(LISA);

        InMemoryContractRepository fresh = new InMemoryContractRepository();
        ContractPresenter presenter = new ContractPresenter(documents);
        ContractService restoreTarget = new ContractService(fresh, documents, presenter,
                ContractTestSupport.household(), clock);
        ContractQueryService restored = new ContractQueryService(fresh, presenter,
                ContractTestSupport.household(), clock);

        assertThat(restoreTarget.importContract(LISA, exported.get(0)))
                .isEqualTo(ImportContract.ImportOutcome.IMPORTED);
        assertThat(restoreTarget.importContract(LISA, exported.get(0)))
                .isEqualTo(ImportContract.ImportOutcome.ALREADY_PRESENT);

        ContractView after = restored.get(LISA, contract.id());
        assertThat(after.facts()).extracting(FactView::id)
                .containsExactlyInAnyOrderElementsOf(contract().facts().stream().map(FactView::id).toList());
        assertThat(fact(after, wrong).reviewState()).isEqualTo("SUPERSEDED");
        assertThat(fact(after, wrong).supersededBy()).isEqualTo(fact(contract(), wrong).supersededBy());
        assertThat(after.deadline().deadline()).isEqualTo(LocalDate.of(2027, 10, 1));
        assertThat(after.sources()).singleElement().extracting(source -> source.documentId())
                .isEqualTo(contractPdf);
    }

    private ContractView enter(ContractId id, long version, String type, String value) {
        return commands.enterFact(LISA, id, new EnterFactCommand(type, value, null, null, contractPdf, null, null,
                null), version);
    }

    private ContractView contract() {
        return queries.get(LISA, queries.list(LISA).get(0).id());
    }

    private static FactView fact(ContractView view, UUID id) {
        return view.facts().stream().filter(fact -> fact.id().equals(id)).findFirst().orElseThrow();
    }

    private static FactView fact(ContractView view, String type) {
        return view.facts().stream().filter(fact -> fact.type().equals(type)).findFirst().orElseThrow();
    }

    private static String effectiveValue(ContractView view, String type) {
        return view.facts().stream().filter(fact -> fact.type().equals(type) && fact.effective()).findFirst()
                .orElseThrow().value();
    }
}
