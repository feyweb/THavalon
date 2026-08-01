package com.thavalon.web;

import com.thavalon.game.GameException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * Turns failures into {@link Api.ErrorResponse}.
 *
 * <p>Extends {@link ResponseEntityExceptionHandler} deliberately. Without it, the catch-all
 * {@code Exception} handler below also swallows Spring's own MVC exceptions — unparseable JSON,
 * the wrong content type, an unknown route, the wrong HTTP method — and reports every one of them
 * as a 500. That hides client mistakes as server faults and turns routine 404s into pages.
 */
@RestControllerAdvice
public class ApiExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(GameException.class)
    public ResponseEntity<Api.ErrorResponse> handleGameException(GameException e) {
        return ResponseEntity.status(e.status())
                .body(new Api.ErrorResponse(e.code(), e.getMessage()));
    }

    /** Domain-level rejections (bad player count, duplicate names) are the caller's fault. */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Api.ErrorResponse> handleIllegalArgument(IllegalArgumentException e) {
        return ResponseEntity.badRequest()
                .body(new Api.ErrorResponse("INVALID_REQUEST", e.getMessage()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Api.ErrorResponse> handleUnexpected(Exception e) {
        log.error("Unhandled error", e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(new Api.ErrorResponse("INTERNAL_ERROR", "Something went wrong. Try again."));
    }

    /** Gives Spring's built-in MVC failures the same response shape as everything else. */
    @Override
    protected ResponseEntity<Object> handleExceptionInternal(
            Exception e, Object body, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        if (status.is5xxServerError()) {
            log.error("Server error handling request", e);
        }
        return ResponseEntity.status(status)
                .body(new Api.ErrorResponse(codeFor(status), messageFor(status)));
    }

    private String codeFor(HttpStatusCode status) {
        if (status.isSameCodeAs(HttpStatus.NOT_FOUND)) return "NOT_FOUND";
        if (status.isSameCodeAs(HttpStatus.METHOD_NOT_ALLOWED)) return "METHOD_NOT_ALLOWED";
        if (status.isSameCodeAs(HttpStatus.UNSUPPORTED_MEDIA_TYPE)) return "UNSUPPORTED_MEDIA_TYPE";
        return status.is4xxClientError() ? "BAD_REQUEST" : "INTERNAL_ERROR";
    }

    private String messageFor(HttpStatusCode status) {
        if (status.isSameCodeAs(HttpStatus.NOT_FOUND)) return "No such endpoint.";
        if (status.is4xxClientError()) return "The request could not be understood.";
        return "Something went wrong. Try again.";
    }
}
