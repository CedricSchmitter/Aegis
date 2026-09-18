package dev.aegis.assistant.application.api;

import dev.aegis.shared.Actor;
import java.util.List;
import java.util.UUID;

/** Member commands on runs. */
public interface AiRunCommands {
    /** Cancels a run; a running attempt stops at its next checkpoint and never applies its result. */
    RunView cancel(Actor actor, UUID runId);

    /** Requests a new run for the same work after a failed or cancelled run; the new run references the old one. */
    RunView retry(Actor actor, UUID runId);

    /** Starts one connection check per distinct configured model. */
    List<RunView> checkConnection(Actor actor);
}
