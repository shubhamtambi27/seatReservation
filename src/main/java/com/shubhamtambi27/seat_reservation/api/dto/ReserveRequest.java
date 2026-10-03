package com.shubhamtambi27.seat_reservation.api.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
@Schema(description = "Seat list to claim. user_id in the body is ignored.")
public record ReserveRequest(
        @Schema(example = "[\"A12\"]") List<String> seats,
        @Schema(example = "req-1", description = "Required unless Idempotency-Key header is set")
                String idempotency_key) {
}
