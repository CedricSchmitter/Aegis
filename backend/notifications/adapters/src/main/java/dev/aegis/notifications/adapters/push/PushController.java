package dev.aegis.notifications.adapters.push;

import dev.aegis.notifications.application.api.PushDispatch;
import dev.aegis.notifications.application.api.PushSettings;
import dev.aegis.shared.Actor;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.time.LocalTime;
import java.util.List;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** The current member's push devices and preferences (issue #28). */
@RestController
@RequestMapping("/api/push")
@Tag(name = "push")
public class PushController {
    private final PushSettings settings;
    private final PushDispatch dispatch;

    public PushController(PushSettings settings, PushDispatch dispatch) {
        this.settings = settings;
        this.dispatch = dispatch;
    }

    public record PushConfigDto(boolean available, @Schema(nullable = true) String publicKey) {
    }

    public record PushDeviceDto(String id, @Schema(nullable = true) String deviceLabel,
            @Schema(allowableValues = {"ACTIVE", "FAILING", "EXPIRED"}) String state, Instant createdAt,
            @Schema(nullable = true) Instant lastSentAt) {
        static PushDeviceDto from(PushSettings.PushDevice d) {
            return new PushDeviceDto(d.id(), d.deviceLabel(), d.state(), d.createdAt(), d.lastSentAt());
        }
    }

    public record SubscribeRequest(@NotBlank String endpoint, @NotBlank String p256dh, @NotBlank String auth,
            @Schema(nullable = true) String deviceLabel) {
    }

    public record PushPreferencesDto(
            @NotNull @ArraySchema(uniqueItems = true, schema = @Schema(
                    allowableValues = {"TASKS", "DEADLINES", "REVIEW", "SYSTEM"})) Set<String> categories,
            @Schema(nullable = true, type = "string", format = "time", example = "22:00:00") LocalTime quietStart,
            @Schema(nullable = true, type = "string", format = "time", example = "07:00:00") LocalTime quietEnd,
            boolean showDetails, long version) {
        static PushPreferencesDto from(PushSettings.Preferences p) {
            return new PushPreferencesDto(p.categories(), p.quietStart(), p.quietEnd(), p.showDetails(),
                    p.version());
        }
    }

    public record TestPushResponse(@Schema(allowableValues = {"SENT", "FAILED", "EXPIRED", "UNAVAILABLE"})
            String outcome) {
    }

    @Operation(operationId = "pushConfig", summary = "Whether pushes can be sent, and the key browsers subscribe with")
    @GetMapping("/config")
    public PushConfigDto config(Actor actor) {
        PushSettings.PushConfig config = settings.config(actor);
        return new PushConfigDto(config.available(), config.publicKey());
    }

    @Operation(operationId = "listPushDevices", summary = "The current member's devices that receive pushes")
    @GetMapping("/devices")
    public List<PushDeviceDto> devices(Actor actor) {
        return settings.devices(actor).stream().map(PushDeviceDto::from).toList();
    }

    @Operation(operationId = "subscribePushDevice",
            summary = "Subscribe this browser for the current member, or renew its subscription")
    @PostMapping("/devices")
    public PushDeviceDto subscribe(Actor actor, @Valid @RequestBody SubscribeRequest body) {
        return PushDeviceDto.from(settings.subscribe(actor,
                new PushSettings.Subscribe(body.endpoint(), body.p256dh(), body.auth(), body.deviceLabel())));
    }

    @Operation(operationId = "removePushDevice", summary = "Stop pushes to one of the current member's devices")
    @DeleteMapping("/devices/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void remove(Actor actor, @PathVariable("id") String id) {
        settings.removeDevice(actor, id);
    }

    @Operation(operationId = "sendTestPush", summary = "Send a test push to one of the current member's devices")
    @PostMapping("/devices/{id}/test")
    public TestPushResponse test(Actor actor, @PathVariable("id") String id) {
        return new TestPushResponse(dispatch.sendTest(actor, id).outcome());
    }

    @Operation(operationId = "pushPreferences", summary = "What is pushed to the current member's devices")
    @GetMapping("/preferences")
    public PushPreferencesDto preferences(Actor actor) {
        return PushPreferencesDto.from(settings.preferences(actor));
    }

    @Operation(operationId = "changePushPreferences",
            summary = "Change the push preferences; a stale version is rejected with 409")
    @PutMapping("/preferences")
    public PushPreferencesDto changePreferences(Actor actor, @Valid @RequestBody PushPreferencesDto body) {
        return PushPreferencesDto.from(settings.changePreferences(actor, new PushSettings.Preferences(
                body.categories(), body.quietStart(), body.quietEnd(), body.showDetails(), body.version())));
    }
}
