package dev.aegis.bootstrap.support;

import dev.aegis.assistant.application.ports.ContentPart;
import dev.aegis.assistant.application.ports.ModelCallException;
import dev.aegis.assistant.application.ports.ModelGateway;
import dev.aegis.assistant.application.ports.ModelRequest;
import dev.aegis.assistant.application.ports.ModelTurn;
import dev.aegis.assistant.domain.FailureCategory;
import dev.aegis.assistant.domain.TokenUsage;
import dev.aegis.shared.MemberId;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

/**
 * Controlled model for integration tests (provider {@code controlled}): answers from a per-test script, can hold a
 * call open to simulate a slow provider, and records every request. Like a personal subscription, it can refuse to
 * serve chosen members. No network, no credentials.
 */
public class ControlledModelGateway implements ModelGateway {
    public static final String PROVIDER = "controlled";
    /** Catalog model of the Sonnet family in the tests, the default family of every workload. */
    public static final String MODEL = "claude-sonnet-controlled";
    public static final TokenUsage USAGE = new TokenUsage(1_000, 100, 0, 0);

    public final List<ModelRequest> requests = new CopyOnWriteArrayList<>();
    private final ConcurrentLinkedDeque<Function<ModelRequest, ModelTurn>> script = new ConcurrentLinkedDeque<>();
    private volatile Function<ModelRequest, ModelTurn> fallback = ControlledModelGateway::unavailable;
    public volatile boolean configured = true;
    /** Members this provider does not serve. */
    public final Set<MemberId> notAllowed = ConcurrentHashMap.newKeySet();

    public void reset() {
        requests.clear();
        notAllowed.clear();
        script.clear();
        fallback = ControlledModelGateway::unavailable;
        configured = true;
    }

    public ControlledModelGateway answer(Function<ModelRequest, ModelTurn> step) {
        script.add(step);
        return this;
    }

    public ControlledModelGateway answer(ModelTurn turn) {
        return answer(request -> turn);
    }

    /** Answer for every call once the script is used up; by default the provider is unavailable. */
    public void otherwise(Function<ModelRequest, ModelTurn> answer) {
        fallback = answer;
    }

    /** A step that signals {@code entered}, then waits for {@code release} before answering. */
    public static Function<ModelRequest, ModelTurn> held(CountDownLatch entered, CountDownLatch release,
            ModelTurn turn) {
        return request -> {
            entered.countDown();
            try {
                if (!release.await(30, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("held model call was never released");
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(interrupted);
            }
            return turn;
        };
    }

    public static ModelTurn json(Map<String, Object> value) {
        String text = dev.aegis.assistant.application.JsonText.write(value);
        return new ModelTurn(List.of(new ContentPart.Text(text)), ModelTurn.Stop.END, USAGE, MODEL + "-1", text,
                value, null);
    }

    public static ModelTurn toolCall(String id, String name, Map<String, Object> input) {
        return new ModelTurn(List.of(new ContentPart.ToolCall(id, name, input)), ModelTurn.Stop.TOOL_USE, USAGE,
                MODEL + "-1", "", null, null);
    }

    public static ModelTurn unavailable(ModelRequest request) {
        throw new ModelCallException(FailureCategory.PROVIDER_UNAVAILABLE, "provider.unavailable",
                "controlled outage");
    }

    @Override
    public String provider() {
        return PROVIDER;
    }

    @Override
    public boolean configured() {
        return configured;
    }

    @Override
    public boolean allows(MemberId member) {
        return !notAllowed.contains(member);
    }

    @Override
    public ModelTurn complete(ModelRequest request) {
        requests.add(request);
        Function<ModelRequest, ModelTurn> next = script.poll();
        return (next == null ? fallback : next).apply(request);
    }
}
