package dev.aegis.operations.adapters.rest;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Build identity of the running instance; public so the login screen can show the deployed commit. */
@RestController
@Tag(name = "system")
public class VersionController {
    private final String commit;
    private final String buildTime;

    public VersionController(@Value("${aegis.build.commit:dev}") String commit,
            @Value("${aegis.build.time:unknown}") String buildTime) {
        this.commit = commit;
        this.buildTime = buildTime;
    }

    public record VersionResponse(String application, String commit, String buildTime) {
    }

    @Operation(operationId = "version", summary = "Deployed commit and build time")
    @GetMapping("/api/system/version")
    public VersionResponse version() {
        return new VersionResponse("aegis", commit, buildTime);
    }
}
