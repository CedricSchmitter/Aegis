package dev.aegis.operations.adapters.rest;

import dev.aegis.operations.application.api.SystemStatus;
import dev.aegis.operations.application.api.SystemStatusQuery;
import dev.aegis.shared.Actor;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Operational status for members. */
@RestController
@RequestMapping("/api/status")
@Tag(name = "status")
public class StatusController {
    private final SystemStatusQuery status;

    public StatusController(SystemStatusQuery status) {
        this.status = status;
    }

    @Operation(operationId = "systemStatus", summary = "Integration and processing status with last success times")
    @GetMapping
    public SystemStatus status(Actor actor) {
        return status.status(actor);
    }
}
