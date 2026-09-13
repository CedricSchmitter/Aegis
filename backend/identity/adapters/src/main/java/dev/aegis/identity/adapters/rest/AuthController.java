package dev.aegis.identity.adapters.rest;

import dev.aegis.identity.adapters.security.ActorArgumentResolver;
import dev.aegis.identity.application.api.MemberPreferences;
import dev.aegis.shared.Actor;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.context.SecurityContextHolderStrategy;
import org.springframework.security.web.authentication.logout.SecurityContextLogoutHandler;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Session login for the web app. Credentials are verified by Spring Security against stored hashes. */
@RestController
@RequestMapping("/api/auth")
@Tag(name = "auth")
public class AuthController {
    private final AuthenticationManager authenticationManager;
    private final SecurityContextRepository securityContextRepository;
    private final SecurityContextHolderStrategy contextHolderStrategy = SecurityContextHolder.getContextHolderStrategy();
    private final MemberPreferences preferences;

    public AuthController(AuthenticationManager authenticationManager,
            SecurityContextRepository securityContextRepository, MemberPreferences preferences) {
        this.authenticationManager = authenticationManager;
        this.securityContextRepository = securityContextRepository;
        this.preferences = preferences;
    }

    public record LoginRequest(@NotBlank String username, @NotBlank String password) {
    }

    public record SessionResponse(MemberDto member) {
    }

    @Operation(operationId = "login", summary = "Start a session with username and password")
    @PostMapping("/login")
    public SessionResponse login(@Valid @RequestBody LoginRequest body, HttpServletRequest request,
            HttpServletResponse response) {
        Authentication authentication = authenticationManager.authenticate(
                UsernamePasswordAuthenticationToken.unauthenticated(body.username(), body.password()));
        if (request.getSession(false) != null) {
            request.changeSessionId();
        }
        SecurityContext context = contextHolderStrategy.createEmptyContext();
        context.setAuthentication(authentication);
        contextHolderStrategy.setContext(context);
        securityContextRepository.saveContext(context, request, response);
        return new SessionResponse(MemberDto.from(preferences.currentMember(ActorArgumentResolver.currentActor())));
    }

    @Operation(operationId = "logout", summary = "End the current session")
    @PostMapping("/logout")
    public ResponseEntity<Void> logout(HttpServletRequest request, HttpServletResponse response) {
        new SecurityContextLogoutHandler().logout(request, response,
                contextHolderStrategy.getContext().getAuthentication());
        return ResponseEntity.status(HttpStatus.NO_CONTENT).build();
    }

    @Operation(operationId = "currentSession", summary = "Return the member of the current session")
    @GetMapping("/session")
    public SessionResponse session(Actor actor) {
        return new SessionResponse(MemberDto.from(preferences.currentMember(actor)));
    }
}
