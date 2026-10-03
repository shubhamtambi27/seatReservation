package com.shubhamtambi27.seat_reservation.api.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record CreateShowRequest(String name, List<String> seats, Long price_paise, Integer per_user_limit) {
}
