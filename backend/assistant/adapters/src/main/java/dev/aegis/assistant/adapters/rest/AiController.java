package dev.aegis.assistant.adapters.rest;

import dev.aegis.assistant.adapters.rest.AiDtos.AiOverviewDto;
import dev.aegis.assistant.adapters.rest.AiDtos.ChooseModelRequest;
import dev.aegis.assistant.adapters.rest.AiDtos.RunDto;
import dev.aegis.assistant.application.api.AiModelCommands;
import dev.aegis.assistant.application.api.AiRunCommands;
import dev.aegis.assistant.application.api.AiRunQueries;
import dev.aegis.shared.Actor;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Visible AI configuration, runs and member commands. Runs execute in the background; no call waits on a model. */
@RestController
@RequestMapping("/api/ai")
@Tag(name = "ai")
public class AiController {
    private final AiRunQueries queries;
    private final AiRunCommands commands;
    private final AiModelCommands models;

    public AiController(AiRunQueries queries, AiRunCommands commands, AiModelCommands models) {
        this.queries = queries;
        this.commands = commands;
        this.models = models;
    }

    @Operation(operationId = "aiOverview", summary = "Configured workloads, budgets and today's AI spending")
    @GetMapping("/overview")
    public AiOverviewDto overview(Actor actor) {
        return AiOverviewDto.from(queries.overview(actor));
    }

    @Operation(operationId = "chooseAiModel", summary = "Choose the model family of a workload for the household",
            description = "Applies to both members and scheduled work from the next run on; queued and running runs "
                    + "keep their model. A stale expectedVersion is rejected with 409.")
    @PutMapping("/workloads/{workload}/model")
    public AiOverviewDto chooseModel(Actor actor, @PathVariable String workload,
            @RequestBody ChooseModelRequest body) {
        return AiOverviewDto.from(models.chooseModel(actor, workload, body.family(), body.expectedVersion()));
    }

    @Operation(operationId = "listAiRuns", summary = "Most recent AI runs of the household")
    @GetMapping("/runs")
    public List<RunDto> runs(Actor actor, @RequestParam(defaultValue = "20") int limit) {
        return queries.recent(actor, limit).stream().map(RunDto::from).toList();
    }

    @Operation(operationId = "getAiRun", summary = "One AI run with its audit steps")
    @GetMapping("/runs/{id}")
    public RunDto run(Actor actor, @PathVariable UUID id) {
        return RunDto.from(queries.get(actor, id));
    }

    @Operation(operationId = "cancelAiRun", summary = "Cancel a run; a running attempt never applies its result")
    @PostMapping("/runs/{id}/cancel")
    public RunDto cancel(Actor actor, @PathVariable UUID id) {
        return RunDto.from(commands.cancel(actor, id));
    }

    @Operation(operationId = "retryAiRun", summary = "Start a new run for the work of a failed or cancelled run")
    @PostMapping("/runs/{id}/retry")
    @ResponseStatus(HttpStatus.CREATED)
    public RunDto retry(Actor actor, @PathVariable UUID id) {
        return RunDto.from(commands.retry(actor, id));
    }

    @Operation(operationId = "checkAiConnection", summary = "Start one connection check per configured model")
    @PostMapping("/connection-checks")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public List<RunDto> checkConnection(Actor actor) {
        return commands.checkConnection(actor).stream().map(RunDto::from).toList();
    }
}
