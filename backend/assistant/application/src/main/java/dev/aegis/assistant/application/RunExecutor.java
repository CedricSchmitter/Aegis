package dev.aegis.assistant.application;

import dev.aegis.assistant.application.chat.ChatTools;
import dev.aegis.assistant.application.ports.AiTool;
import dev.aegis.assistant.application.ports.ContentPart;
import dev.aegis.assistant.application.ports.ModelCallException;
import dev.aegis.assistant.application.ports.ModelGateway;
import dev.aegis.assistant.application.ports.ModelMessage;
import dev.aegis.assistant.application.ports.ModelRequest;
import dev.aegis.assistant.application.ports.ModelTurn;
import dev.aegis.assistant.application.ports.ToolSpec;
import dev.aegis.assistant.domain.AiRun;
import dev.aegis.assistant.domain.FailureCategory;
import dev.aegis.assistant.domain.RunId;
import dev.aegis.assistant.domain.RunState;
import dev.aegis.assistant.domain.Workload;
import dev.aegis.shared.InvalidInputException;
import dev.aegis.shared.NotAuthorizedException;
import dev.aegis.shared.NotFoundException;
import dev.aegis.shared.StaleVersionException;
import java.lang.System.Logger.Level;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Executes one attempt of a run: prepares the workload input, alternates bounded model calls and granted read-only
 * tool calls, validates the final answer and hands it to the transactional completion. Runs outside any database
 * transaction; every decision about budgets, cancellation and state is taken by {@link RunExecution}.
 *
 * <p>Model and tool failures are classified and recorded; the outcome tells the job when to execute again.
 */
public class RunExecutor {
    /** Largest stored result. */
    static final int MAX_OUTPUT_CHARS = 64 * 1024;
    /** Largest tool result handed to the model. */
    static final int MAX_TOOL_RESULT_CHARS = 20_000;
    private static final System.Logger LOG = System.getLogger(RunExecutor.class.getName());

    /** What the job should do after this execution. */
    public sealed interface Outcome permits Done, RunAgainAt {
    }

    public record Done() implements Outcome {
    }

    public record RunAgainAt(Instant at) implements Outcome {
    }

    private final RunExecution execution;
    private final Map<Workload, WorkloadHandler> handlers;
    private final Map<String, ModelGateway> gateways;
    private final Map<String, AiTool> tools;
    private final AiSettings settings;

    public RunExecutor(RunExecution execution, Map<Workload, WorkloadHandler> handlers,
            Map<String, ModelGateway> gateways, Map<String, AiTool> tools, AiSettings settings) {
        this.execution = execution;
        this.handlers = Map.copyOf(handlers);
        this.gateways = Map.copyOf(gateways);
        this.tools = Map.copyOf(tools);
        this.settings = settings;
    }

    /**
     * Executes the run once. An execution that ended an attempt or the run may have freed a slot; it is offered to the
     * oldest waiting run right away instead of leaving that run to its slot wait.
     */
    public Outcome execute(RunId id) {
        return switch (execution.begin(id)) {
            case RunExecution.Finished finished -> {
                offerFreeSlots(id);
                yield new Done();
            }
            case RunExecution.Later later -> new RunAgainAt(later.at());
            case RunExecution.Proceed proceed -> {
                Outcome outcome = attempt(proceed.context());
                offerFreeSlots(id);
                yield outcome;
            }
        };
    }

    /**
     * Completes a slot hand-over that raced with this run's execution: called after the job stored the time of
     * {@link RunAgainAt}, it moves the job to now if a slot was offered to the run meanwhile.
     */
    public void rescheduled(RunId id) {
        try {
            execution.wakeIfOffered(id);
        } catch (RuntimeException failure) {
            LOG.log(Level.WARNING, "AI run " + id + " could not take up an offered slot and starts after the slot "
                    + "wait: " + failure.getClass().getSimpleName());
        }
    }

    /** A failed hand-over only delays waiting runs to their slot wait; the outcome of this execution stands. */
    private void offerFreeSlots(RunId after) {
        try {
            execution.offerFreeSlots();
        } catch (RuntimeException failure) {
            LOG.log(Level.WARNING, "the AI slot freed by run " + after + " was not handed over; waiting runs start "
                    + "after the slot wait: " + failure.getClass().getSimpleName());
        }
    }

    private Outcome attempt(RunContext run) {
        try {
            return converse(run);
        } catch (ModelCallException failure) {
            return fail(run, failure.category(), failure.code(), failure.getMessage(), failure.retryAfter());
        } catch (InvalidModelOutputException invalid) {
            return fail(run, FailureCategory.INVALID_OUTPUT, invalid.code(), invalid.getMessage(), null);
        } catch (NotAuthorizedException forbidden) {
            return fail(run, FailureCategory.NOT_AUTHORIZED, "auth.forbidden",
                    "the requesting member is not allowed to read a source or use a tool", null);
        } catch (StaleVersionException stale) {
            // Runs are locked per step, so a conflict comes from a source edited while the result was applied.
            return fail(run, FailureCategory.STALE_SOURCE, "source.changed", stale.resource() + " changed", null);
        } catch (NotFoundException missing) {
            return fail(run, FailureCategory.STALE_SOURCE, "source.missing", missing.getMessage(), null);
        } catch (SourceUnavailableException unavailable) {
            return fail(run, FailureCategory.SOURCE_UNAVAILABLE, "source.unavailable", unavailable.getMessage(), null);
        } catch (RuntimeException unexpected) {
            LOG.log(Level.ERROR, "AI run " + run.id() + " attempt " + run.attempt() + " failed unexpectedly",
                    unexpected);
            return fail(run, FailureCategory.INTERNAL, "internal.error", unexpected.getClass().getSimpleName(), null);
        }
    }

    private Outcome converse(RunContext run) {
        WorkloadHandler handler = handlers.get(run.workload());
        if (handler == null) {
            throw new ModelCallException(FailureCategory.NOT_CONFIGURED, "ai.workload.notImplemented",
                    "no handler is registered for workload " + run.workload());
        }
        ModelGateway gateway = gateways.get(run.model().provider());
        if (gateway == null || !gateway.configured()) {
            throw new ModelCallException(FailureCategory.NOT_CONFIGURED, "ai.provider.notConfigured",
                    "provider " + run.model().provider() + " has no server-side credentials");
        }
        if (!gateway.allows(run.actor().memberId())) {
            throw new ModelCallException(FailureCategory.NOT_AUTHORIZED, "ai.provider.memberNotAllowed",
                    "provider " + run.model().provider() + " is not available for the requesting member");
        }
        ModelPrice price = settings.prices().find(run.model().model())
                .orElseThrow(() -> new ModelCallException(FailureCategory.NOT_CONFIGURED, "ai.price.unknown",
                        "no price is configured for model " + run.model().model()));
        List<ToolSpec> offered = offeredTools(handler);
        Set<String> offeredNames = new LinkedHashSet<>(offered.stream().map(ToolSpec::name).toList());
        PreparedRun prepared = handler.prepare(run);
        String system = prepared.system() + "\n\n" + UntrustedContent.SYSTEM_RULES;
        List<ModelMessage> conversation = new ArrayList<>(prepared.messages());
        Set<String> incompleteRetrieval = new LinkedHashSet<>();
        while (true) {
            long estimate = TokenEstimate.inputTokens(system, conversation, offered, prepared.outputSchema());
            AiRun.Checkpoint admission = execution.beforeModelCall(run.id(), run.attempt(),
                    new AiRun.CallQuote(price.inputCost(estimate), price.outputPerMillion()));
            if (!(admission instanceof AiRun.Allowed allowed)) {
                return after(admission);
            }
            Duration timeout = allowed.remaining().compareTo(settings.callTimeout()) < 0 ? allowed.remaining()
                    : settings.callTimeout();
            ModelTurn turn = gateway.complete(new ModelRequest(run.model().model(), system, conversation, offered,
                    prepared.outputSchema(), allowed.maxOutputTokens(), timeout, run.effort()));
            AiRun.Checkpoint recorded = execution.afterModelCall(run.id(), run.attempt(), turn.modelId(),
                    "stop." + turn.stop().name().toLowerCase(Locale.ROOT), turn.usage(), price.cost(turn.usage()));
            if (!(recorded instanceof AiRun.Continue)) {
                return after(recorded);
            }
            switch (turn.stop()) {
                case REFUSAL -> throw new ModelCallException(FailureCategory.REFUSED, "model.refused",
                        "the model declined the request");
                case MAX_TOKENS -> throw new InvalidModelOutputException("output.truncated",
                        "the answer exceeded the output allowance of " + allowed.maxOutputTokens() + " tokens");
                case OTHER -> throw new InvalidModelOutputException("output.unexpectedStop",
                        "the model stopped without a final answer");
                case TOOL_USE -> {
                    Outcome halted = callTools(run, turn, offeredNames, conversation, incompleteRetrieval);
                    if (halted != null) {
                        return halted;
                    }
                }
                case END -> {
                    ValidatedOutput output = handler.validate(run, turn, Set.copyOf(incompleteRetrieval));
                    if (output.json().length() > MAX_OUTPUT_CHARS) {
                        throw new InvalidModelOutputException("output.tooLarge",
                                "the answer exceeds " + MAX_OUTPUT_CHARS + " characters");
                    }
                    return after(execution.complete(run.id(), run.attempt(), output));
                }
            }
        }
    }

    /** Runs the requested tools; returns an outcome when the attempt must stop, otherwise null. */
    private Outcome callTools(RunContext run, ModelTurn turn, Set<String> offeredNames,
            List<ModelMessage> conversation, Set<String> incompleteRetrieval) {
        List<ContentPart.ToolCall> calls = turn.toolCalls();
        if (calls.isEmpty()) {
            throw new InvalidModelOutputException("output.missingToolCall",
                    "the model announced a tool call without requesting one");
        }
        conversation.add(turn.asAssistantMessage());
        List<ContentPart.ToolResult> results = new ArrayList<>();
        for (ContentPart.ToolCall call : calls) {
            boolean granted = offeredNames.contains(call.name());
            AiRun.Checkpoint gate = execution.beforeToolCall(run.id(), run.attempt(), call.name(), granted);
            switch (gate) {
                case AiRun.Continue allowed -> results.add(invoke(run, call, incompleteRetrieval));
                case AiRun.Denied denied -> results.add(new ContentPart.ToolResult(call.id(),
                        JsonText.write(Map.of("error", denied.code(),
                                "detail", "This tool is not available for this task.")), true));
                default -> {
                    return after(gate);
                }
            }
        }
        conversation.add(ModelMessage.toolResults(results));
        return null;
    }

    private ContentPart.ToolResult invoke(RunContext run, ContentPart.ToolCall call, Set<String> incompleteRetrieval) {
        AiTool tool = tools.get(call.name());
        try {
            AiTool.Result result = tool.invoke(new AiTool.Invocation(run.id(), run.actor()), call.input());
            if (result.retrievalIncomplete() || (isDocumentSearch(call.name()) && result.error())) {
                incompleteRetrieval.add(call.name());
            }
            return new ContentPart.ToolResult(call.id(), bounded(result.content()), result.error());
        } catch (NotAuthorizedException forbidden) {
            throw forbidden;
        } catch (InvalidInputException invalid) {
            return toolError(call, invalid.code());
        } catch (NotFoundException missing) {
            return toolError(call, missing.resource() + ".notFound");
        } catch (RuntimeException failure) {
            LOG.log(Level.WARNING, "tool " + call.name() + " failed in AI run " + run.id() + ": "
                    + failure.getClass().getSimpleName());
            if (isDocumentSearch(call.name())) incompleteRetrieval.add(call.name());
            return toolError(call, "tool.failed");
        }
    }

    private static ContentPart.ToolResult toolError(ContentPart.ToolCall call, String code) {
        return new ContentPart.ToolResult(call.id(), JsonText.write(Map.of("error", code)), true);
    }

    private static boolean isDocumentSearch(String name) {
        return ChatTools.SEARCH_DOCUMENTS.equals(name)
                || ChatTools.SEARCH_DOCUMENT_TEXT.equals(name);
    }

    private static String bounded(String content) {
        if (content.length() <= MAX_TOOL_RESULT_CHARS) {
            return content;
        }
        return content.substring(0, MAX_TOOL_RESULT_CHARS) + "\n[truncated: " + MAX_TOOL_RESULT_CHARS + " of "
                + content.length() + " characters shown]";
    }

    private List<ToolSpec> offeredTools(WorkloadHandler handler) {
        return handler.grantedTools().stream().map(tools::get).filter(tool -> tool != null).map(AiTool::spec)
                .sorted(Comparator.comparing(ToolSpec::name)).toList();
    }

    private Outcome fail(RunContext run, FailureCategory category, String code, String message, Duration retryAfter) {
        return after(execution.fail(run.id(), run.attempt(), category, code, message, retryAfter));
    }

    private static Outcome after(AiRun.Checkpoint checkpoint) {
        if (checkpoint instanceof AiRun.Halted halted && halted.state() == RunState.WAITING_RETRY
                && halted.nextAttemptAt() != null) {
            return new RunAgainAt(halted.nextAttemptAt());
        }
        return new Done();
    }
}
