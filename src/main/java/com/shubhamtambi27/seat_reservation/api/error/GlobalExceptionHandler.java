package com.shubhamtambi27.seat_reservation.api.error;

import com.shubhamtambi27.seat_reservation.config.RequestIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<Map<String, String>> handleApi(ApiException ex) {
        return body(ex.getStatus(), ex.getError(), ex.getMessage());
    }

    @ExceptionHandler({QueryTimeoutException.class, DataAccessResourceFailureException.class})
    public ResponseEntity<Map<String, String>> handleBusy(Exception ex) {
        log.warn("dependency_busy", ex);
        return body(HttpStatus.TOO_MANY_REQUESTS, "busy", "Service is busy; retry with the same idempotency key");
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Map<String, String>> handleBadJson(HttpMessageNotReadableException ex) {
        return body(HttpStatus.BAD_REQUEST, "invalid_json", "Request body is not valid JSON");
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<Map<String, String>> handleNotFound(NoResourceFoundException ex) {
        return body(HttpStatus.NOT_FOUND, "not_found", "No such endpoint");
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, String>> handleUnknown(Exception ex, HttpServletRequest request) {
        log.error("unhandled_error method={} path={}", request.getMethod(), request.getRequestURI(), ex);
        return body(HttpStatus.INTERNAL_SERVER_ERROR, "internal_error", "Unexpected server error");
    }

    private static ResponseEntity<Map<String, String>> body(HttpStatus status, String error, String message) {
        return ResponseEntity.status(status).body(Map.of(
                "error", error,
                "message", message,
                "request_id", String.valueOf(MDC.get(RequestIdFilter.MDC_KEY))));
    }
}
