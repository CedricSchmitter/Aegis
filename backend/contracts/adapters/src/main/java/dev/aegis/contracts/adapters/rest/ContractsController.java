package dev.aegis.contracts.adapters.rest;

import dev.aegis.contracts.adapters.rest.ContractDto.SummaryDto;
import dev.aegis.contracts.application.api.ContractCommands;
import dev.aegis.contracts.application.api.ContractCommands.CorrectFactCommand;
import dev.aegis.contracts.application.api.ContractCommands.CreateContractCommand;
import dev.aegis.contracts.application.api.ContractCommands.EnterFactCommand;
import dev.aegis.contracts.application.api.ContractDeadlineQuery;
import dev.aegis.contracts.application.api.ContractQueries;
import dev.aegis.shared.Actor;
import dev.aegis.shared.ContractId;
import dev.aegis.shared.DocumentId;
import dev.aegis.shared.InvalidInputException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Contracts, their facts and the confirmed cancellation deadlines. Every write is a member decision, so the
 * household can record and review contracts without AI. Version-checked requests answer 409 instead of
 * overwriting a concurrent change.
 */
@RestController
@RequestMapping("/api/contracts")
@Tag(name = "contracts")
public class ContractsController {
    private final ContractCommands commands;
    private final ContractQueries queries;
    private final ContractDeadlineQuery deadlines;

    public ContractsController(ContractCommands commands, ContractQueries queries,
            ContractDeadlineQuery deadlines) {
        this.commands = commands;
        this.queries = queries;
        this.deadlines = deadlines;
    }

    /** The counterparty is optional; when given it is stored as a confirmed fact of the source document. */
    public record CreateContractRequest(@NotBlank String name, @Schema(nullable = true) String counterparty,
            @Schema(nullable = true) String sourceDocumentId) {
    }

    /** Named explicitly because the documents context publishes its own {@code RenameRequest}. */
    @Schema(name = "RenameContractRequest")
    public record RenameRequest(@NotBlank String name, long expectedVersion) {
    }

    public record StatusRequest(@NotBlank String status, long expectedVersion) {
    }

    /**
     * A fact a member states. {@code value} is the canonical form of the type: {@code 2028-01-01} for a date,
     * {@code P3M} for a period, {@code 29.90 EUR} for an amount, {@code true}/{@code false} for a rule, text
     * otherwise. {@code sourcePage} and {@code sourceQuote} are only sent when the document really says so.
     */
    public record EnterFactRequest(@NotBlank String type, @NotBlank String value,
            @Schema(nullable = true) LocalDate validFrom, @Schema(nullable = true) LocalDate validUntil,
            @Schema(nullable = true) String sourceDocumentId, @Schema(nullable = true) Integer sourcePage,
            @Schema(nullable = true) String sourceQuote, @Schema(nullable = true) String note,
            long expectedVersion) {
    }

    public record CorrectFactRequest(@NotBlank String value, @Schema(nullable = true) LocalDate validFrom,
            @Schema(nullable = true) LocalDate validUntil, @Schema(nullable = true) String note,
            long expectedVersion) {
    }

    /** Named explicitly because planning and the label catalog publish their own {@code VersionRequest}. */
    @Schema(name = "ContractVersionRequest")
    public record VersionRequest(long expectedVersion) {
    }

    /** A confirmed cancellation deadline with the source it rests on. */
    public record DeadlineItemDto(String contractId, String name, @Schema(nullable = true) String counterparty,
            @Schema(nullable = true) LocalDate contractEnd, LocalDate cancellationDeadline, String basis,
            List<String> basedOnFactIds, @Schema(nullable = true) String sourceDocumentId, String status,
            long contractVersion) {
    }

    @Operation(operationId = "listContracts", summary = "List contracts, optionally only those of one document")
    @GetMapping
    public List<SummaryDto> list(Actor actor,
            @RequestParam(name = "documentId", required = false) String documentId) {
        if (documentId != null && !documentId.isBlank()) {
            return queries.forDocument(actor, DocumentId.of(documentId)).stream().map(ContractDto::from).toList();
        }
        return queries.list(actor).stream().map(ContractDto::from).toList();
    }

    @Operation(operationId = "createContract", summary = "Create a contract, optionally with its counterparty")
    @PostMapping
    public ResponseEntity<ContractDto> create(Actor actor, @Valid @RequestBody CreateContractRequest body) {
        ContractDto created = ContractDto.from(commands.create(actor, new CreateContractCommand(body.name(),
                body.counterparty(), documentId(body.sourceDocumentId()))));
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    @Operation(operationId = "getContract",
            summary = "Contract with every observation, its provenance and the calculated deadline")
    @GetMapping("/{id}")
    public ContractDto get(Actor actor, @PathVariable("id") String id) {
        return ContractDto.from(queries.get(actor, ContractId.of(id)));
    }

    @Operation(operationId = "renameContract", summary = "Change the household name of the contract")
    @PutMapping("/{id}")
    public ContractDto rename(Actor actor, @PathVariable("id") String id, @Valid @RequestBody RenameRequest body) {
        return ContractDto.from(commands.rename(actor, ContractId.of(id), body.name(), body.expectedVersion()));
    }

    @Operation(operationId = "listTrashedContracts",
            summary = "Contracts in the trash, most recently trashed first")
    @GetMapping("/trash")
    public List<ContractDto.SummaryDto> trashed(Actor actor) {
        return queries.trashed(actor).stream().map(ContractDto::from).toList();
    }

    @Operation(operationId = "trashContract",
            summary = "Move a contract to the trash; its facts stay and it can be restored")
    @PostMapping("/{id}/trash")
    public ContractDto trash(Actor actor, @PathVariable("id") String id, @RequestBody VersionRequest body) {
        return ContractDto.from(commands.moveToTrash(actor, ContractId.of(id), body.expectedVersion()));
    }

    @Operation(operationId = "restoreContract", summary = "Bring a contract back from the trash")
    @PostMapping("/{id}/restore")
    public ContractDto restore(Actor actor, @PathVariable("id") String id, @RequestBody VersionRequest body) {
        return ContractDto.from(commands.restoreFromTrash(actor, ContractId.of(id), body.expectedVersion()));
    }

    @Operation(operationId = "changeContractStatus",
            summary = "Record that the contract is active, cancelled or ended")
    @PostMapping("/{id}/status")
    public ContractDto status(Actor actor, @PathVariable("id") String id, @Valid @RequestBody StatusRequest body) {
        return ContractDto.from(commands.changeStatus(actor, ContractId.of(id), body.status(),
                body.expectedVersion()));
    }

    @Operation(operationId = "enterContractFact",
            summary = "State a fact about the contract; it counts as confirmed because a member entered it")
    @PostMapping("/{id}/facts")
    public ContractDto enterFact(Actor actor, @PathVariable("id") String id,
            @Valid @RequestBody EnterFactRequest body) {
        return ContractDto.from(commands.enterFact(actor, ContractId.of(id), new EnterFactCommand(body.type(),
                body.value(), body.validFrom(), body.validUntil(), documentId(body.sourceDocumentId()),
                body.sourcePage(), body.sourceQuote(), body.note()), body.expectedVersion()));
    }

    @Operation(operationId = "confirmContractFact", summary = "Accept a proposed fact")
    @PostMapping("/{id}/facts/{factId}/confirm")
    public ContractDto confirmFact(Actor actor, @PathVariable("id") String id,
            @PathVariable("factId") String factId, @RequestBody VersionRequest body) {
        return ContractDto.from(commands.confirmFact(actor, ContractId.of(id), factId(factId),
                body.expectedVersion()));
    }

    @Operation(operationId = "rejectContractFact",
            summary = "Reject a fact; it stays visible as history and stops being effective")
    @PostMapping("/{id}/facts/{factId}/reject")
    public ContractDto rejectFact(Actor actor, @PathVariable("id") String id,
            @PathVariable("factId") String factId, @RequestBody VersionRequest body) {
        return ContractDto.from(commands.rejectFact(actor, ContractId.of(id), factId(factId),
                body.expectedVersion()));
    }

    @Operation(operationId = "correctContractFact",
            summary = "Correct a fact; the corrected observation is kept as superseded history")
    @PostMapping("/{id}/facts/{factId}/correct")
    public ContractDto correctFact(Actor actor, @PathVariable("id") String id,
            @PathVariable("factId") String factId, @Valid @RequestBody CorrectFactRequest body) {
        return ContractDto.from(commands.correctFact(actor, ContractId.of(id), factId(factId),
                new CorrectFactCommand(body.value(), body.validFrom(), body.validUntil(), body.note()),
                body.expectedVersion()));
    }

    @Operation(operationId = "listContractDeadlines",
            summary = "Contracts whose confirmed facts establish a cancellation deadline")
    @GetMapping("/deadlines")
    public List<DeadlineItemDto> deadlines(Actor actor,
            @RequestParam(name = "on", required = false) LocalDate on) {
        return deadlines.confirmedDeadlines(actor, on).stream().map(view -> new DeadlineItemDto(
                view.contractId().toString(), view.name(), view.counterparty(), view.contractEnd(),
                view.cancellationDeadline(), view.basis(),
                view.basedOnFactIds().stream().map(Object::toString).toList(),
                view.sourceDocumentId() == null ? null : view.sourceDocumentId().toString(), view.status(),
                view.contractVersion())).toList();
    }

    private static DocumentId documentId(String value) {
        return value == null || value.isBlank() ? null : DocumentId.of(value);
    }

    private static UUID factId(String value) {
        try {
            return UUID.fromString(value.trim());
        } catch (IllegalArgumentException | NullPointerException invalid) {
            throw new InvalidInputException("validation.failed", "Invalid fact id");
        }
    }
}
