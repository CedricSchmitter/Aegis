package dev.aegis.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.aegis.bootstrap.support.PostgresIT;
import dev.aegis.contracts.application.api.ContractCommands;
import dev.aegis.contracts.application.api.ContractCommands.CorrectFactCommand;
import dev.aegis.contracts.application.api.ContractCommands.CreateContractCommand;
import dev.aegis.contracts.application.api.ContractCommands.EnterFactCommand;
import dev.aegis.contracts.application.api.ContractDeadlineQuery;
import dev.aegis.contracts.application.api.ContractDeadlineQuery.ContractDeadlineView;
import dev.aegis.contracts.application.api.ContractQueries;
import dev.aegis.contracts.application.api.ContractViews.ContractView;
import dev.aegis.contracts.application.api.ContractViews.FactView;
import dev.aegis.contracts.application.api.RecordContractObservation;
import dev.aegis.contracts.application.api.RecordContractObservation.ObservationCommand;
import dev.aegis.documents.application.api.SubjectView;
import dev.aegis.documents.application.api.UploadDocument;
import dev.aegis.documents.application.api.UploadDocument.UploadCommand;
import dev.aegis.identity.application.api.MemberCredentialsQuery;
import dev.aegis.portability.application.api.CreateExport;
import dev.aegis.portability.application.api.ExportSummary;
import dev.aegis.portability.application.api.RestoreExport;
import dev.aegis.shared.Actor;
import dev.aegis.shared.ContractId;
import dev.aegis.shared.DocumentId;
import dev.aegis.shared.StaleVersionException;
import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Contract facts against a real PostgreSQL: typed values and provenance survive a round trip through the
 * database, concurrent decisions are rejected instead of merged, and an original contract, a later amendment and
 * a contradicting invoice keep their review states and relationships across export and restore.
 */
class ContractFactsIT extends PostgresIT {
    @Autowired
    UploadDocument upload;
    @Autowired
    ContractCommands contracts;
    @Autowired
    ContractQueries contractQueries;
    @Autowired
    ContractDeadlineQuery deadlines;
    @Autowired
    RecordContractObservation observations;
    @Autowired
    CreateExport createExport;
    @Autowired
    RestoreExport restore;
    @Autowired
    MemberCredentialsQuery members;
    @Autowired
    JdbcTemplate jdbc;

    @Test
    void typedValuesAndProvenanceSurviveTheDatabase() throws Exception {
        Actor lisa = actor(ANNA);
        DocumentId contractPdf = uploadDocument(lisa, "Fitnessstudio Vertrag.pdf");

        ContractView contract = contracts.create(lisa,
                new CreateContractCommand("Fitnessstudio", "McFit GmbH", contractPdf));
        contract = enter(lisa, contract, "END_DATE", "2028-01-01", 2, "Vertragsende: 01.01.2028", contractPdf);
        contract = enter(lisa, contract, "NOTICE_PERIOD", "P3M", 3, "Kündigungsfrist: 3 Monate", contractPdf);
        contract = enter(lisa, contract, "AMOUNT", "29.90 EUR", null, null, contractPdf);
        contract = enter(lisa, contract, "AUTOMATIC_RENEWAL", "true", null, null, contractPdf);

        ContractView reloaded = contractQueries.get(actor(BEN), contract.id());
        assertThat(reloaded.counterparty()).isEqualTo("McFit GmbH");
        assertThat(reloaded.facts()).extracting(FactView::type, FactView::value, FactView::reviewState)
                .contains(org.assertj.core.groups.Tuple.tuple("END_DATE", "2028-01-01", "CONFIRMED"),
                        org.assertj.core.groups.Tuple.tuple("NOTICE_PERIOD", "P3M", "CONFIRMED"),
                        org.assertj.core.groups.Tuple.tuple("AMOUNT", "29.90 EUR", "CONFIRMED"),
                        org.assertj.core.groups.Tuple.tuple("AUTOMATIC_RENEWAL", "true", "CONFIRMED"));
        FactView end = fact(reloaded, "END_DATE");
        assertThat(end.source().page()).isEqualTo(2);
        assertThat(end.source().quote()).isEqualTo("Vertragsende: 01.01.2028");
        assertThat(end.source().documentTitle()).isEqualTo("Fitnessstudio Vertrag");
        assertThat(reloaded.deadline().determined()).isTrue();
        assertThat(reloaded.deadline().deadline()).isEqualTo(LocalDate.of(2027, 10, 1));

        // Money keeps decimal precision and its currency in their own columns, not as free text.
        UUID amountFactId = fact(reloaded, "AMOUNT").id();
        assertThat(jdbc.queryForObject("SELECT value_amount FROM contract_facts WHERE id = ?", BigDecimal.class,
                amountFactId)).isEqualByComparingTo(new BigDecimal("29.90"));
        assertThat(jdbc.queryForObject("SELECT value_currency FROM contract_facts WHERE id = ?", String.class,
                amountFactId)).isEqualTo("EUR");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM contract_facts WHERE contract_id = ?", Long.class,
                contract.id().value())).isEqualTo(5);
    }

    @Test
    void twoMembersDecidingAtOnceDoNotOverwriteEachOther() throws Exception {
        Actor lisa = actor(ANNA);
        Actor cedric = actor(BEN);
        DocumentId contractPdf = uploadDocument(lisa, "Versicherung Vertrag.pdf");
        ContractView contract = contracts.create(lisa, new CreateContractCommand("Versicherung", null, null));
        ContractId id = contract.id();
        long shared = contract.version();

        contracts.enterFact(lisa, id, new EnterFactCommand("END_DATE", "2028-01-01", null, null, contractPdf, null,
                null, null), shared);
        assertThatThrownBy(() -> contracts.enterFact(cedric, id, new EnterFactCommand("END_DATE", "2029-01-01",
                null, null, contractPdf, null, null, null), shared)).isInstanceOf(StaleVersionException.class);

        // The same race from two threads: exactly one of them wins, and the loser is told to reload.
        long current = contractQueries.get(lisa, id).version();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Callable<Boolean> enterNotice = () -> {
                try {
                    contracts.enterFact(lisa, id, new EnterFactCommand("NOTICE_PERIOD", "P3M", null, null,
                            contractPdf, null, null, null), current);
                    return true;
                } catch (StaleVersionException | org.springframework.dao.ConcurrencyFailureException rejected) {
                    return false;
                }
            };
            List<Future<Boolean>> results = pool.invokeAll(List.of(enterNotice, enterNotice));
            int accepted = 0;
            for (Future<Boolean> result : results) {
                accepted += result.get() ? 1 : 0;
            }
            assertThat(accepted).isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
        assertThat(contractQueries.get(lisa, id).facts()).filteredOn(fact -> fact.type().equals("NOTICE_PERIOD"))
                .hasSize(1);
    }

    @Test
    void originalContractAmendmentAndContradictingInvoiceSurviveExportAndRestore() throws Exception {
        Actor lisa = actor(ANNA);
        Actor cedric = actor(BEN);
        DocumentId contractPdf = uploadDocument(lisa, "Studio Vertrag.pdf");
        DocumentId amendmentPdf = uploadDocument(lisa, "Studio Nachtrag.pdf");
        DocumentId invoicePdf = uploadDocument(cedric, "Studio Rechnung.pdf");

        ContractView contract = contracts.create(lisa,
                new CreateContractCommand("Fitnessstudio Nord", "McFit GmbH", contractPdf));
        contract = enter(lisa, contract, "END_DATE", "2028-01-01", 1, "Vertragsende: 01.01.2028", contractPdf);
        contract = enter(lisa, contract, "AMOUNT", "29.90 EUR", 1, "monatlich 29,90 EUR", contractPdf);
        // The amendment raises the amount from its own start date and states the cancellation date itself.
        contract = contracts.enterFact(lisa, contract.id(), new EnterFactCommand("AMOUNT", "34.90 EUR",
                LocalDate.of(2027, 1, 1), null, amendmentPdf, 1, "ab 01.01.2027: 34,90 EUR", "Nachtrag"),
                contract.version());
        contract = contracts.enterFact(lisa, contract.id(), new EnterFactCommand("CANCELLATION_DEADLINE",
                "2027-11-30", null, null, amendmentPdf, 2, "Kündigung bis 30.11.2027", "Nachtrag"),
                contract.version());
        // An extraction run reads a different amount from an invoice: a review item, never a silent overwrite.
        var contradiction = observations.observe(cedric, contract.id(), new ObservationCommand("AMOUNT",
                "24.90 EUR", null, null, invoicePdf, 1, "Rechnungsbetrag 24,90 EUR", 0.83, "run:invoice"));
        assertThat(contradiction.outcome()).isEqualTo("CONTRADICTS_CONFIRMED");

        ContractId id = contract.id();
        assertExpectedState(cedric, id);
        ExportSummary export = createExport.export(lisa);
        assertThat(export.contractCount()).isGreaterThanOrEqualTo(1);
        Path generation = Path.of(export.location());
        String contractsCsv = Files.readString(generation.resolve("readable/contracts.csv"),
                StandardCharsets.UTF_8);
        assertThat(contractsCsv).contains("Fitnessstudio Nord").contains("34.90 EUR").contains("EXTRACTED");
        assertThat(Files.isRegularFile(generation.resolve("data/contracts.json"))).isTrue();

        List<FactView> before = contractQueries.get(lisa, id).facts();
        jdbc.execute("TRUNCATE TABLE contract_facts, contracts");
        assertThat(contractQueries.list(lisa)).isEmpty();

        RestoreExport.RestoreReport report = restore.restore(lisa, export.location());
        assertThat(report.contractsImported()).isGreaterThanOrEqualTo(1);
        assertThat(report.contractsAlreadyPresent()).isZero();

        ContractView restored = contractQueries.get(cedric, id);
        assertThat(restored.facts()).extracting(FactView::id)
                .containsExactlyInAnyOrderElementsOf(before.stream().map(FactView::id).toList());
        assertExpectedState(cedric, id);
        // A second restore changes nothing, so a repeated drill never duplicates the fact history.
        assertThat(restore.restore(lisa, export.location()).contractsAlreadyPresent()).isGreaterThanOrEqualTo(1);
        assertThat(contractQueries.get(lisa, id).facts()).hasSameSizeAs(before);
    }

    @Test
    void aCorrectedDeadlineReplacesTheOldOneAndKeepsItReadable() throws Exception {
        Actor lisa = actor(ANNA);
        DocumentId contractPdf = uploadDocument(lisa, "Mobilfunk Vertrag.pdf");
        ContractView contract = contracts.create(lisa, new CreateContractCommand("Mobilfunk", null, null));
        contract = enter(lisa, contract, "CANCELLATION_DEADLINE", "2027-11-03", 1, "bis 03.11.2027", contractPdf);
        UUID wrong = fact(contract, "CANCELLATION_DEADLINE").id();

        contract = contracts.correctFact(lisa, contract.id(), wrong,
                new CorrectFactCommand("2027-11-30", null, null, "Zahlendreher"), contract.version());

        ContractView reloaded = contractQueries.get(lisa, contract.id());
        assertThat(reloaded.facts()).hasSize(2);
        FactView corrected = reloaded.facts().stream().filter(fact -> fact.id().equals(wrong)).findFirst()
                .orElseThrow();
        assertThat(corrected.reviewState()).isEqualTo("SUPERSEDED");
        assertThat(corrected.supersededBy()).isNotNull();
        assertThat(corrected.value()).isEqualTo("2027-11-03");
        assertThat(deadline(lisa, contract.id()).cancellationDeadline()).isEqualTo(LocalDate.of(2027, 11, 30));
    }

    /** The state the household expects before and after a restore: amendment effective, contradiction pending. */
    private void assertExpectedState(Actor actor, ContractId id) {
        ContractView view = contractQueries.get(actor, id);
        // The amendment does not replace the original amount; it takes over from its own start date.
        assertThat(view.facts()).filteredOn(fact -> fact.type().equals("AMOUNT")
                        && fact.reviewState().equals("CONFIRMED"))
                .extracting(FactView::value, FactView::validFrom)
                .containsExactlyInAnyOrder(org.assertj.core.groups.Tuple.tuple("29.90 EUR", null),
                        org.assertj.core.groups.Tuple.tuple("34.90 EUR", LocalDate.of(2027, 1, 1)));
        assertThat(view.facts()).filteredOn(fact -> fact.type().equals("AMOUNT")).hasSize(3);
        assertThat(view.facts()).filteredOn(fact -> fact.reviewState().equals("EXTRACTED")).singleElement()
                .extracting(FactView::value).isEqualTo("24.90 EUR");
        assertThat(view.conflicts()).singleElement().extracting(conflict -> conflict.kind())
                .isEqualTo("EXTRACTED_AGAINST_CONFIRMED");
        assertThat(view.sources()).hasSize(3);

        ContractDeadlineView deadline = deadline(actor, id);
        assertThat(deadline.cancellationDeadline()).isEqualTo(LocalDate.of(2027, 11, 30));
        assertThat(deadline.contractEnd()).isEqualTo(LocalDate.of(2028, 1, 1));
        assertThat(deadline.basis()).isEqualTo("STATED_DEADLINE");
        assertThat(deadline.counterparty()).isEqualTo("McFit GmbH");
        assertThat(deadline.sourceDocumentId()).isNotNull();
    }

    private ContractDeadlineView deadline(Actor actor, ContractId id) {
        LocalDate today = LocalDate.of(2026, 9, 20);
        assertThat(deadlines.confirmedDeadlines(actor, today))
                .extracting(ContractDeadlineView::contractId).contains(id);
        return deadlines.confirmedDeadline(actor, id, today).orElseThrow();
    }

    private ContractView enter(Actor actor, ContractView contract, String type, String value, Integer page,
            String quote, DocumentId source) {
        return contracts.enterFact(actor, contract.id(), new EnterFactCommand(type, value, null, null, source,
                page, quote, null), contract.version());
    }

    private DocumentId uploadDocument(Actor actor, String filename) throws Exception {
        byte[] fixture = Files.readAllBytes(FIXTURE);
        byte[] marker = (" %% " + filename + " " + System.nanoTime() + " %% ")
                .getBytes(StandardCharsets.US_ASCII);
        byte[] pdf = new byte[fixture.length + marker.length];
        System.arraycopy(fixture, 0, pdf, 0, fixture.length);
        System.arraycopy(marker, 0, pdf, fixture.length, marker.length);
        return upload.upload(actor, new UploadCommand(filename, "application/pdf", pdf.length,
                new ByteArrayInputStream(pdf), List.of(new SubjectView("HOUSE", null)))).document().id();
    }

    private Actor actor(String username) {
        return new Actor(members.findCredentials(username).orElseThrow().id());
    }

    private static FactView fact(ContractView view, String type) {
        return view.facts().stream().filter(fact -> fact.type().equals(type)).findFirst().orElseThrow();
    }
}
