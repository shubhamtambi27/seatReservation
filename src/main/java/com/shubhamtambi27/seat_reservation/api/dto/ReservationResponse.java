package com.shubhamtambi27.seat_reservation.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import java.util.UUID;

@Schema(description = "Reservation. status is confirmed or cancelled. user_id is always the token's user.")
public record ReservationResponse(
        UUID reservation_id,
        UUID show_id,
        String user_id,
        List<String> seats,
        long amount_paise,
        String status) {
}
