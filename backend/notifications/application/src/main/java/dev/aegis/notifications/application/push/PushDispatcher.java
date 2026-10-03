package dev.aegis.notifications.application.push;

import dev.aegis.notifications.application.api.PushDispatch;
import dev.aegis.shared.Actor;

/**
 * Sends what the ledger decided, outside any database transaction, and hands each outcome back to the ledger. A
 * crash between sending and recording repeats the push on the next run; the message's tag and topic make the device
 * and the push service replace the earlier copy instead of showing it twice.
 */
public class PushDispatcher implements PushDispatch {
    private final PushLedger ledger;
    private final PushSender sender;

    public PushDispatcher(PushLedger ledger, PushSender sender) {
        this.ledger = ledger;
        this.sender = sender;
    }

    @Override
    public Report dispatchPending() {
        int sent = 0;
        int failed = 0;
        int expired = 0;
        RuntimeException firstError = null;
        for (PendingPush push : ledger.pending()) {
            try {
                PushSender.Result result = sender.send(push.endpoint(), push.message());
                ledger.recordOutcome(push, result);
                switch (result.outcome()) {
                    case ACCEPTED -> sent++;
                    case GONE -> expired++;
                    case REJECTED, UNAVAILABLE, NOT_CONFIGURED -> failed++;
                }
            } catch (RuntimeException e) {
                // The other devices still get their pushes; the run then fails so the status page shows it.
                failed++;
                if (firstError == null) {
                    firstError = e;
                }
            }
        }
        if (firstError != null) {
            throw firstError;
        }
        return new Report(sent, failed, expired);
    }

    @Override
    public TestResult sendTest(Actor actor, String deviceId) {
        PendingPush push = ledger.testPush(actor, deviceId);
        PushSender.Result result = sender.send(push.endpoint(), push.message());
        ledger.recordOutcome(push, result);
        return new TestResult(switch (result.outcome()) {
            case ACCEPTED -> "SENT";
            case GONE -> "EXPIRED";
            case REJECTED, UNAVAILABLE -> "FAILED";
            case NOT_CONFIGURED -> "UNAVAILABLE";
        });
    }
}
