package com.shubhamtambi27.seat_reservation.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import java.util.UUID;

@Schema(description = "Show inventory. available + held + confirmed == total_seats.")
public record ShowResponse(
        UUID id,
        String name,
        long price_paise,
        int per_user_limit,
        int total_seats,
        int available,
        int held,
        int confirmed,
        List<SeatView> seats) {
}
