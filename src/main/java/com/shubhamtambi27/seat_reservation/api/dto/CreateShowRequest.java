package com.shubhamtambi27.seat_reservation.api.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
@Schema(description = "Create a show. per_user_limit defaults to 4.")
public record CreateShowRequest(
        @Schema(example = "friday-night") String name,
        @Schema(example = "[\"A1\",\"A2\",\"A12\"]") List<String> seats,
        @Schema(example = "25000", description = "Integer minor units (paise)") Long price_paise,
        @Schema(example = "4") Integer per_user_limit) {
}
