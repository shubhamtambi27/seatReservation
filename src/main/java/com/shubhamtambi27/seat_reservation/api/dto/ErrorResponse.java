package com.shubhamtambi27.seat_reservation.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Domain or validation error")
public record ErrorResponse(
        @Schema(example = "seat_taken") String error,
        @Schema(example = "One or more requested seats are not available") String message,
        @Schema(example = "8f2c0e3a-1b2d-4c5e-9a0b-123456789abc") String request_id) {
}
