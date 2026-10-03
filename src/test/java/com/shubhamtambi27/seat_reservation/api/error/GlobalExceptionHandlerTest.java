package com.shubhamtambi27.seat_reservation.api.error;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;

class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    void mapsApiExceptionStatusAndErrorCode() {
        MDC.put("request_id", "req-1");
        ResponseEntity<Map<String, String>> response =
                handler.handleApi(ApiException.conflict("seat_taken", "taken"));
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody()).containsEntry("error", "seat_taken");
        assertThat(response.getBody()).containsEntry("request_id", "req-1");
    }

    @Test
    void mapsBadJsonAndUnknownErrors() {
        assertThat(handler.handleBadJson(mock(HttpMessageNotReadableException.class)).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);

        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getMethod()).thenReturn("POST");
        when(request.getRequestURI()).thenReturn("/shows");
        ResponseEntity<Map<String, String>> unknown = handler.handleUnknown(new RuntimeException("boom"), request);
        assertThat(unknown.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(unknown.getBody()).containsEntry("error", "internal_error");
    }
}
