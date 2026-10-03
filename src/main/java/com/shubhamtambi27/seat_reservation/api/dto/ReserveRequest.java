package com.shubhamtambi27.seat_reservation.api.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record ReserveRequest(List<String> seats, String idempotency_key) {
}
