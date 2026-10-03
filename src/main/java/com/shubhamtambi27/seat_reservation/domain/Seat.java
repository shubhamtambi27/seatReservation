package com.shubhamtambi27.seat_reservation.domain;

import java.util.UUID;

public record Seat(String label, String status, UUID reservationId) {
}
