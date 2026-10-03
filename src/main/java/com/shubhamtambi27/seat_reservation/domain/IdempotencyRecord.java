package com.shubhamtambi27.seat_reservation.domain;

import java.util.UUID;

public record IdempotencyRecord(String requestFingerprint, UUID reservationId) {
}
