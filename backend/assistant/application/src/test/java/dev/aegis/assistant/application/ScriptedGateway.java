package dev.aegis.assistant.application;

import dev.aegis.assistant.application.ports.ContentPart;
import dev.aegis.assistant.application.ports.ModelCallException;
import dev.aegis.assistant.application.ports.ModelGateway;
import dev.aegis.assistant.application.ports.ModelRequest;
import dev.aegis.assistant.application.ports.ModelTurn;
import dev.aegis.assistant.domain.FailureCategory;
import dev.aegis.assistant.domain.TokenUsage;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/** Controlled model: answers from a script and records every request it received. */
class ScriptedGateway implements ModelGateway {
    static final String PROVIDER = "scripted";
    static final TokenUsage USAGE = new TokenUsage(1_000, 100, 0, 0);

    final List<ModelRequest> requests = new ArrayList<>();
    private final Deque<Function<ModelRequest, ModelTurn>> script = new ArrayDeque<>();
    boolean configured = true;
    /** Members whose runs may use this provider; null allows everybody. */
    java.util.Set<dev.aegis.shared.MemberId> allowedMembers;

    @Override
    public String provider() {
        return PROVIDER;
    }

    @Override
    public boolean allows(dev.aegis.shared.MemberId member) {
        return allowedMembers == null || allowedMembers.contains(member);
    }

    @Override
    public boolean configured() {
        return configured;
    }

    @Override
    public synchronized ModelTurn complete(ModelRequest request) {
        requests.add(request);
        Function<ModelRequest, ModelTurn> next = script.poll();
        if (next == null) {
            throw new AssertionError("unexpected model call " + requests.size());
        }
        return next.apply(request);
    }

    ScriptedGateway answer(Function<ModelRequest, ModelTurn> step) {
        script.add(step);
        return this;
    }

    ScriptedGateway answer(ModelTurn turn) {
        return answer(request -> turn);
    }

    ScriptedGateway fail(FailureCategory category, String code, Duration retryAfter) {
        return answer(request -> {
            throw new ModelCallException(category, code, "scripted " + code, retryAfter, null);
        });
    }

    static ModelTurn json(Map<String, Object> value) {
        String text = JsonText.write(value);
        return new ModelTurn(List.of(new ContentPart.Text(text)), ModelTurn.Stop.END, USAGE, "scripted-model-1", text,
                value, null);
    }

    static ModelTurn text(String text) {
        return new ModelTurn(List.of(new ContentPart.Text(text)), ModelTurn.Stop.END, USAGE, "scripted-model-1", text,
                null, null);
    }

    static ModelTurn stop(ModelTurn.Stop stop) {
        return new ModelTurn(List.of(new ContentPart.Text("{\"lab")), stop, USAGE, "scripted-model-1", "{\"lab", null,
                null);
    }

    static ModelTurn toolCalls(ContentPart.ToolCall... calls) {
        return new ModelTurn(List.of(calls), ModelTurn.Stop.TOOL_USE, USAGE, "scripted-model-1", "", null,
                "opaque-echo");
    }

    static ContentPart.ToolCall call(String id, String name, Map<String, Object> input) {
        return new ContentPart.ToolCall(id, name, input);
    }
}
