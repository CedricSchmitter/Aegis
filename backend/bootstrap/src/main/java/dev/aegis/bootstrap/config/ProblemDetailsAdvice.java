package dev.aegis.bootstrap.config;

import dev.aegis.documents.application.api.OriginalUnavailableException;
import dev.aegis.documents.application.ports.ArchiveUnavailableException;
import dev.aegis.shared.InvalidInputException;
import dev.aegis.shared.NotAuthorizedException;
import dev.aegis.shared.NotFoundException;
import dev.aegis.shared.StaleVersionException;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

/**
 * Maps application exceptions to RFC 9457 problem details with a stable machine-readable {@code code} that the UI
 * localizes. Stack traces never reach clients; unexpected failures carry a correlation id that is also logged.
 */
@RestControllerAdvice
@org.springframework.core.annotation.Order(org.springframework.core.Ordered.HIGHEST_PRECEDENCE)
public class ProblemDetailsAdvice {
    private static final Logger log = LoggerFactory.getLogger(ProblemDetailsAdvice.class);

    @ExceptionHandler(NotFoundException.class)
    ProblemDetail notFound(NotFoundException e) {
        ProblemDetail problem = problem(HttpStatus.NOT_FOUND, e.resource() + ".notFound", e.getMessage());
        problem.setProperty("resource", e.resource());
        problem.setProperty("id", e.id());
        return problem;
    }

    @ExceptionHandler(StaleVersionException.class)
    ProblemDetail conflict(StaleVersionException e) {
        ProblemDetail problem = problem(HttpStatus.CONFLICT, "version.conflict", e.getMessage());
        problem.setProperty("resource", e.resource());
        problem.setProperty("id", e.id());
        problem.setProperty("expectedVersion", e.expectedVersion());
        problem.setProperty("actualVersion", e.actualVersion());
        return problem;
    }

    @ExceptionHandler(InvalidInputException.class)
    ProblemDetail invalid(InvalidInputException e) {
        return problem(HttpStatus.BAD_REQUEST, e.code(), e.getMessage());
    }

    @ExceptionHandler(NotAuthorizedException.class)
    ProblemDetail forbidden(NotAuthorizedException e) {
        return problem(HttpStatus.FORBIDDEN, "auth.forbidden", "Access denied");
    }

    @ExceptionHandler(AuthenticationException.class)
    ProblemDetail badCredentials(AuthenticationException e) {
        return problem(HttpStatus.UNAUTHORIZED, "auth.invalidCredentials", "Username or password is wrong");
    }

    @ExceptionHandler(OriginalUnavailableException.class)
    ProblemDetail originalUnavailable(OriginalUnavailableException e) {
        return problem(HttpStatus.SERVICE_UNAVAILABLE, "document.originalUnavailable", e.getMessage());
    }

    @ExceptionHandler(ArchiveUnavailableException.class)
    ProblemDetail archiveUnavailable(ArchiveUnavailableException e) {
        return problem(HttpStatus.SERVICE_UNAVAILABLE, "paperless.unavailable", e.getMessage());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ProblemDetail validation(MethodArgumentNotValidException e) {
        ProblemDetail problem = problem(HttpStatus.BAD_REQUEST, "validation.failed", "Request validation failed");
        problem.setProperty("fields", e.getBindingResult().getFieldErrors().stream()
                .map(f -> f.getField() + ": " + f.getDefaultMessage()).toList());
        return problem;
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    ProblemDetail tooLarge(MaxUploadSizeExceededException e) {
        return problem(HttpStatus.PAYLOAD_TOO_LARGE, "document.tooLarge", "Upload exceeds the size limit");
    }

    @ExceptionHandler({org.springframework.http.converter.HttpMessageNotReadableException.class,
            org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class,
            org.springframework.web.multipart.support.MissingServletRequestPartException.class,
            org.springframework.web.bind.MissingServletRequestParameterException.class})
    ProblemDetail malformedRequest(Exception e) {
        return problem(HttpStatus.BAD_REQUEST, "validation.failed", "Request validation failed");
    }

    @ExceptionHandler(Exception.class)
    ProblemDetail unexpected(Exception e) {
        String correlationId = UUID.randomUUID().toString();
        log.error("Unexpected failure {}", correlationId, e);
        ProblemDetail problem = problem(HttpStatus.INTERNAL_SERVER_ERROR, "internal.error",
                "Unexpected failure; correlation id " + correlationId);
        problem.setProperty("correlationId", correlationId);
        return problem;
    }

    private static ProblemDetail problem(HttpStatus status, String code, String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setProperty("code", code);
        return problem;
    }
}
