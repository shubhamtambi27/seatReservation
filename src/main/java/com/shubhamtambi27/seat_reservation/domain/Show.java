package com.shubhamtambi27.seat_reservation.domain;

import java.time.OffsetDateTime;
import java.util.UUID;

public record Show(UUID id, String name, long pricePaise, int perUserLimit, OffsetDateTime createdAt) {
}
