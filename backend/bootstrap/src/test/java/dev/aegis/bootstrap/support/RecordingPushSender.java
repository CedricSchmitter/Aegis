package dev.aegis.bootstrap.support;

import dev.aegis.notifications.application.push.PushMessage;
import dev.aegis.notifications.application.push.PushSender;
import dev.aegis.notifications.domain.push.PushEndpoint;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/** Replaces the push service in integration tests: records each message and answers with a chosen outcome. */
public class RecordingPushSender implements PushSender {
    public record Sent(String endpoint, PushMessage message) {
    }

    public final List<Sent> sent = new CopyOnWriteArrayList<>();
    private volatile Result answer = new Result(Outcome.ACCEPTED, 201);

    public void reset() {
        sent.clear();
        answer = new Result(Outcome.ACCEPTED, 201);
    }

    public void answer(Outcome outcome, Integer status) {
        answer = new Result(outcome, status);
    }

    @Override
    public Result send(PushEndpoint endpoint, PushMessage message) {
        sent.add(new Sent(endpoint.url(), message));
        return answer;
    }
}
