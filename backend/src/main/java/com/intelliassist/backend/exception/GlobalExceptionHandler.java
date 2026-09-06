package com.intelliassist.backend.exception;

import com.intelliassist.backend.config.CorrelationIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * Translates exceptions into the single {@link ApiError} shape.
 *
 * <p>The rule this enforces: a client learns what it did wrong and nothing about
 * how the system is built. No stack traces, no SQL, no class names, no
 * upstream provider messages. Those go to the log, joined to the response by
 * the request id.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /** Every deliberate application failure. */
    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ApiError> handleApiException(
            ApiException exception, HttpServletRequest request) {

        // 4xx is the caller's problem and is normal traffic; 5xx is ours.
        if (exception.getStatus().is5xxServerError()) {
            log.error("{}: {}", exception.getCode(), exception.getMessage(), exception);
        } else {
            log.warn("{}: {}", exception.getCode(), exception.getMessage());
        }

        return build(
                exception.getStatus(),
                exception.getCode(),
                exception.getMessage(),
                request);
    }

    /** Bean Validation failures on a request body. */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiError> handleValidation(
            MethodArgumentNotValidException exception, HttpServletRequest request) {

        List<ApiError.FieldProblem> problems =
                exception.getBindingResult().getFieldErrors().stream()
                        .map(
                                error ->
                                        new ApiError.FieldProblem(
                                                error.getField(),
                                                error.getDefaultMessage() == null
                                                        ? "is invalid"
                                                        : error.getDefaultMessage()))
                        .toList();

        log.warn("validation failed on {} ({} field(s))", request.getRequestURI(), problems.size());

        return ResponseEntity.badRequest()
                .body(
                        ApiError.validation(
                                "Request validation failed",
                                request.getRequestURI(),
                                requestId(),
                                problems));
    }

    /**
     * Constraint violations on request parameters.
     *
     * <p>Raised by {@code @Validated} on a controller, which covers
     * {@code @RequestParam} and {@code @PathVariable}. Distinct from
     * {@link MethodArgumentNotValidException}, which covers request bodies -
     * without this, an out-of-range page size would surface as a 500.
     */
    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ApiError> handleConstraintViolation(
            ConstraintViolationException exception, HttpServletRequest request) {

        List<ApiError.FieldProblem> problems =
                exception.getConstraintViolations().stream()
                        .map(
                                violation ->
                                        new ApiError.FieldProblem(
                                                lastNode(violation.getPropertyPath().toString()),
                                                violation.getMessage()))
                        .toList();

        log.warn("parameter validation failed on {}", request.getRequestURI());

        return ResponseEntity.badRequest()
                .body(
                        ApiError.validation(
                                "Request validation failed",
                                request.getRequestURI(),
                                requestId(),
                                problems));
    }

    /**
     * A path variable or parameter that could not be converted.
     *
     * <p>Most often a malformed UUID. That is a client mistake, so it is a 400 -
     * left unhandled it would be a 500 and look like our bug.
     */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiError> handleTypeMismatch(
            MethodArgumentTypeMismatchException exception, HttpServletRequest request) {

        log.warn("bad parameter '{}' on {}", exception.getName(), request.getRequestURI());

        return ResponseEntity.badRequest()
                .body(
                        ApiError.validation(
                                "Request validation failed",
                                request.getRequestURI(),
                                requestId(),
                                List.of(
                                        new ApiError.FieldProblem(
                                                exception.getName(), "is not a valid value"))));
    }

    /** Malformed JSON. */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiError> handleUnreadable(
            HttpMessageNotReadableException exception, HttpServletRequest request) {

        // The exception message can quote the offending payload, which may
        // contain a password on the auth routes.
        log.warn("malformed request body on {}", request.getRequestURI());
        return build(
                HttpStatus.BAD_REQUEST,
                "MALFORMED_REQUEST",
                "Request body could not be parsed",
                request);
    }

    @ExceptionHandler(NoHandlerFoundException.class)
    public ResponseEntity<ApiError> handleNotFound(
            NoHandlerFoundException exception, HttpServletRequest request) {
        return build(HttpStatus.NOT_FOUND, "NOT_FOUND", "No such endpoint", request);
    }

    /**
     * A static resource that does not exist.
     *
     * <p>Separate from {@link NoHandlerFoundException}: a URL under a mapped
     * resource handler reaches the handler and fails there, so it never
     * produces a "no handler" error. Without this it fell through to the
     * catch-all below and a plain missing file was reported as a 500 -- which
     * reads as "the server is broken" rather than "that file is not here", and
     * would page someone at three in the morning over a bad favicon path.
     */
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ApiError> handleMissingResource(
            NoResourceFoundException exception, HttpServletRequest request) {
        return build(HttpStatus.NOT_FOUND, "NOT_FOUND", "No such resource", request);
    }

    /**
     * Spring Security's own access-denied exception.
     *
     * <p>Distinct from {@link AccessDeniedException}: this one comes from the
     * filter chain and method security, ours from the service layer.
     */
    @ExceptionHandler(org.springframework.security.access.AccessDeniedException.class)
    public ResponseEntity<ApiError> handleSpringAccessDenied(
            org.springframework.security.access.AccessDeniedException exception,
            HttpServletRequest request) {

        log.warn("access denied on {}", request.getRequestURI());
        return build(HttpStatus.FORBIDDEN, "FORBIDDEN", "Access denied", request);
    }

    /**
     * The catch-all.
     *
     * <p>Logs everything, returns nothing. An unexpected exception means a bug,
     * and its message can name tables, drivers or credentials.
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> handleUnexpected(
            Exception exception, HttpServletRequest request) {

        log.error("unhandled {} on {}", exception.getClass().getSimpleName(),
                request.getRequestURI(), exception);

        return build(
                HttpStatus.INTERNAL_SERVER_ERROR,
                "INTERNAL_ERROR",
                "An internal error occurred",
                request);
    }

    private ResponseEntity<ApiError> build(
            HttpStatus status, String code, String message, HttpServletRequest request) {
        return ResponseEntity.status(status)
                .body(ApiError.of(status.value(), code, message, request.getRequestURI(), requestId()));
    }

    private static String requestId() {
        return MDC.get(CorrelationIdFilter.MDC_KEY);
    }

    /** "list.size" -> "size": the client knows the parameter, not the method. */
    private static String lastNode(String propertyPath) {
        int lastDot = propertyPath.lastIndexOf('.');
        return lastDot >= 0 ? propertyPath.substring(lastDot + 1) : propertyPath;
    }
}
