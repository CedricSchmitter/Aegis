package dev.aegis.contracts.application;

import static dev.aegis.contracts.application.ContractTestSupport.CEDRIC;
import static dev.aegis.contracts.application.ContractTestSupport.LISA;
import static dev.aegis.contracts.application.ContractTestSupport.STRANGER;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.aegis.contracts.application.ContractTestSupport.KnownDocuments;
import dev.aegis.contracts.application.api.ContractCommands.CreateContractCommand;
import dev.aegis.contracts.application.api.ContractCommands.EnterFactCommand;
import dev.aegis.contracts.application.api.ContractViews.ContractSummary;
import dev.aegis.contracts.application.api.ContractViews.ContractView;
import dev.aegis.contracts.application.api.ImportContract;
import dev.aegis.shared.ContractId;
import dev.aegis.shared.DocumentId;
import dev.aegis.shared.NotAuthorizedException;
import dev.aegis.shared.NotFoundException;
import dev.aegis.shared.PurgeConflictException;
import dev.aegis.shared.StaleVersionException;
import dev.aegis.shared.Trash;
import dev.aegis.shared.TrashedException;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Trash and restore of contracts (ADR-0010): facts stay, the published deadline disappears. */
class ContractTrashServiceTest {
    private static final LocalDate TODAY = LocalDate.of(2026, 9, 20);
    private final InMemoryContractRepository repository = new InMemoryContractRepository();
    private final KnownDocuments documents = new KnownDocuments();
    private final Clock clock = ContractTestSupport.clockAt("2026-09-20T08:00:00Z");
    private final List<ContractId> reconciliations = new ArrayList<>();
    private final List<ContractId> purged = new ArrayList<>();
    private ContractService commands;
    private ContractQueryService queries;
    private DocumentId contractPdf;

    @BeforeEach
    void setUp() {
        ContractPresenter presenter = new ContractPresenter(documents);
        commands = new ContractService(repository, documents, presenter, ContractTestSupport.household(), clock,
                (contractId, requestedBy) -> reconciliations.add(contractId), Trash.RecoveryWindow.DEFAULT,
                purged::add);
        queries = new ContractQueryService(repository, presenter, ContractTestSupport.household(), clock);
        contractPdf = documents.add("Fitnessstudio Vertrag");
    }

    /** A contract with a confirmed cancellation deadline on 2027-09-30. */
    private ContractView contractWithDeadline() {
        ContractView contract = commands.create(LISA, new CreateContractCommand("Fitnessstudio", "Fit GmbH",
                contractPdf));
        contract = commands.enterFact(LISA, contract.id(), new EnterFactCommand("END_DATE", "2027-12-31", null,
                null, contractPdf, null, null, null), contract.version());
        return commands.enterFact(LISA, contract.id(), new EnterFactCommand("NOTICE_PERIOD", "P3M", null, null,
                contractPdf, null, null, null), contract.version());
    }

    @Test
    void aTrashedContractLeavesListsAndPublishesNoDeadlineButKeepsItsFacts() {
        ContractView contract = contractWithDeadline();
        assertThat(queries.confirmedDeadline(LISA, contract.id(), TODAY)).isPresent();
        reconciliations.clear();

        ContractView trashed = commands.moveToTrash(LISA, contract.id(), contract.version());

        assertThat(trashed.trash()).isEqualTo(new Trash(LISA.memberId(), clock.instant(),
                clock.instant().plus(Duration.ofDays(30))));
        assertThat(trashed.facts()).hasSize(contract.facts().size());
        assertThat(queries.list(LISA)).isEmpty();
        assertThat(queries.forDocument(LISA, contractPdf)).isEmpty();
        assertThat(queries.confirmedDeadline(LISA, contract.id(), TODAY)).isEmpty();
        assertThat(queries.confirmedDeadlines(LISA, TODAY)).isEmpty();
        assertThat(queries.trashed(LISA)).extracting(ContractSummary::id).containsExactly(contract.id());
        assertThat(queries.get(CEDRIC, contract.id()).trash()).isNotNull();
        assertThat(reconciliations).containsExactly(contract.id());
    }

    @Test
    void restoringPublishesTheDeadlineAgainAndARepeatedRequestIsHarmless() {
        ContractView contract = contractWithDeadline();
        ContractView trashed = commands.moveToTrash(LISA, contract.id(), contract.version());
        assertThat(commands.moveToTrash(CEDRIC, contract.id(), contract.version()).trash())
                .isEqualTo(trashed.trash());
        assertThatThrownBy(() -> commands.restoreFromTrash(LISA, contract.id(), contract.version()))
                .isInstanceOf(StaleVersionException.class);
        reconciliations.clear();

        ContractView restored = commands.restoreFromTrash(CEDRIC, contract.id(), trashed.version());

        assertThat(restored.trash()).isNull();
        assertThat(queries.confirmedDeadline(LISA, contract.id(), TODAY)).get()
                .satisfies(deadline -> assertThat(deadline.cancellationDeadline())
                        .isEqualTo(LocalDate.of(2027, 9, 30)));
        assertThat(queries.list(LISA)).extracting(ContractSummary::id).containsExactly(contract.id());
        assertThat(reconciliations).containsExactly(contract.id());
        assertThat(commands.restoreFromTrash(LISA, contract.id(), trashed.version()).version())
                .isEqualTo(restored.version());
    }

    @Test
    void memberDecisionsWaitForTheRestoreAndATrashedDocumentCannotBeCited() {
        ContractView contract = contractWithDeadline();
        ContractView trashed = commands.moveToTrash(LISA, contract.id(), contract.version());

        assertThatThrownBy(() -> commands.rename(LISA, contract.id(), "Neu", trashed.version()))
                .isInstanceOf(TrashedException.class).hasFieldOrPropertyWithValue("code", "contract.trashed");
        assertThatThrownBy(() -> commands.changeStatus(LISA, contract.id(), "CANCELLED", trashed.version()))
                .isInstanceOf(TrashedException.class);

        DocumentId trashedScan = documents.add("Scan im Papierkorb");
        documents.trash(trashedScan);
        assertThatThrownBy(() -> commands.create(LISA, new CreateContractCommand("Strom", "Stadtwerke",
                trashedScan))).isInstanceOf(TrashedException.class)
                .hasFieldOrPropertyWithValue("code", "document.trashed");
    }

    @Test
    void aFactSourceInTheTrashIsShownAsSuch() {
        ContractView contract = contractWithDeadline();
        documents.trash(contractPdf);

        ContractView view = queries.get(LISA, contract.id());

        assertThat(view.sources()).singleElement().satisfies(source -> {
            assertThat(source.documentTrashed()).isTrue();
            assertThat(source.documentTitle()).isEqualTo("Fitnessstudio Vertrag");
        });
        assertThat(queries.confirmedDeadline(LISA, contract.id(), TODAY)).isPresent();
    }

    @Test
    void exportAndRestoreKeepTheTrashRecord() {
        ContractView contract = contractWithDeadline();
        commands.moveToTrash(LISA, contract.id(), contract.version());
        var exported = queries.exportAll(LISA);
        assertThat(exported).singleElement().satisfies(record -> assertThat(record.trash()).isNotNull());

        InMemoryContractRepository fresh = new InMemoryContractRepository();
        ContractPresenter presenter = new ContractPresenter(documents);
        ContractService restoreTarget = new ContractService(fresh, documents, presenter,
                ContractTestSupport.household(), clock, (contractId, requestedBy) -> { },
                Trash.RecoveryWindow.DEFAULT, purged::add);
        ContractQueryService restored = new ContractQueryService(fresh, presenter, ContractTestSupport.household(),
                clock);

        assertThat(restoreTarget.importContract(LISA, exported.get(0)))
                .isEqualTo(ImportContract.ImportOutcome.IMPORTED);
        assertThat(restored.list(LISA)).isEmpty();
        assertThat(restored.trashed(LISA)).singleElement()
                .satisfies(summary -> assertThat(summary.trash()).isEqualTo(exported.get(0).trash()));
    }

    @Test
    void onlyHouseholdMembersUseTheTrash() {
        ContractView contract = contractWithDeadline();

        assertThatThrownBy(() -> commands.moveToTrash(STRANGER, contract.id(), contract.version()))
                .isInstanceOf(NotAuthorizedException.class);
        assertThatThrownBy(() -> queries.trashed(STRANGER)).isInstanceOf(NotAuthorizedException.class);
    }

    @Test
    void onlyATrashedContractIsDeletedPermanentlyWithItsFactsAndPlanningIsAsked() {
        ContractView contract = contractWithDeadline();
        assertThatThrownBy(() -> commands.purge(LISA, contract.id(), contract.version()))
                .isInstanceOf(PurgeConflictException.class)
                .hasFieldOrPropertyWithValue("code", "contract.purge.notTrashed");
        ContractView trashed = commands.moveToTrash(LISA, contract.id(), contract.version());
        assertThatThrownBy(() -> commands.purge(LISA, contract.id(), trashed.version() + 1))
                .isInstanceOf(StaleVersionException.class);
        assertThatThrownBy(() -> commands.purge(STRANGER, contract.id(), trashed.version()))
                .isInstanceOf(NotAuthorizedException.class);
        reconciliations.clear();

        commands.purge(CEDRIC, contract.id(), trashed.version());

        assertThat(repository.findById(contract.id())).isEmpty();
        assertThat(queries.wasPurged(LISA, contract.id())).isTrue();
        assertThat(queries.trashed(LISA)).isEmpty();
        assertThat(reconciliations).containsExactly(contract.id());
        assertThat(purged).containsExactly(contract.id());
        assertThatThrownBy(() -> queries.get(LISA, contract.id())).isInstanceOf(NotFoundException.class);
        // A repeated request after a lost response changes nothing.
        commands.purge(LISA, contract.id(), trashed.version());
        assertThat(purged).hasSize(1);
        assertThatThrownBy(() -> commands.purge(LISA, ContractId.random(), 0)).isInstanceOf(NotFoundException.class);
    }

    @Test
    void aFactWhoseSourceWasDeletedPermanentlyKeepsPageAndQuoteAndSaysSo() {
        ContractView contract = commands.create(LISA, new CreateContractCommand("Strom", null, null));
        contract = commands.enterFact(LISA, contract.id(), new EnterFactCommand("NOTICE_PERIOD", "P3M", null, null,
                contractPdf, 2, "drei Monate zum Laufzeitende", null), contract.version());

        documents.purge(contractPdf);

        var source = queries.get(LISA, contract.id()).facts().getFirst().source();
        assertThat(source.documentDeleted()).isTrue();
        assertThat(source.documentTitle()).isNull();
        assertThat(source.page()).isEqualTo(2);
        assertThat(source.quote()).isEqualTo("drei Monate zum Laufzeitende");
    }
}
