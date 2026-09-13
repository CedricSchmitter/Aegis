package dev.aegis.identity.adapters.rest;

import dev.aegis.identity.application.api.MemberDirectory;
import dev.aegis.identity.application.api.MemberPreferences;
import dev.aegis.shared.Actor;
import dev.aegis.shared.ProductLocale;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Household member directory and self-service preferences. */
@RestController
@RequestMapping("/api/members")
@Tag(name = "members")
public class MembersController {
    private final MemberDirectory directory;
    private final MemberPreferences preferences;

    public MembersController(MemberDirectory directory, MemberPreferences preferences) {
        this.directory = directory;
        this.preferences = preferences;
    }

    public record LocaleRequest(@NotNull ProductLocale locale) {
    }

    public record PasswordChangeRequest(@NotBlank String currentPassword, @NotBlank String newPassword) {
    }

    @Operation(operationId = "listMembers", summary = "List all household members")
    @GetMapping
    public List<MemberDto> list(Actor actor) {
        return directory.listMembers(actor).stream().map(MemberDto::from).toList();
    }

    @Operation(operationId = "changeLocale", summary = "Change the preferred product locale of the current member")
    @PutMapping("/me/locale")
    public MemberDto changeLocale(Actor actor, @Valid @RequestBody LocaleRequest body) {
        return MemberDto.from(preferences.changeLocale(actor, body.locale()));
    }

    @Operation(operationId = "changePassword", summary = "Change the password of the current member")
    @PostMapping("/me/password")
    public ResponseEntity<Void> changePassword(Actor actor, @Valid @RequestBody PasswordChangeRequest body) {
        preferences.changePassword(actor, body.currentPassword(), body.newPassword());
        return ResponseEntity.noContent().build();
    }
}
