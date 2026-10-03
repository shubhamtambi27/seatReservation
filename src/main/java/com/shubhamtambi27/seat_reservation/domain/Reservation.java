package com.shubhamtambi27.seat_reservation.domain;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public record Reservation(
        UUID id,
        UUID showId,
        String userId,
        long amountPaise,
        String status,
        String idempotencyKey,
        List<String> seats,
        OffsetDateTime createdAt) {
}
