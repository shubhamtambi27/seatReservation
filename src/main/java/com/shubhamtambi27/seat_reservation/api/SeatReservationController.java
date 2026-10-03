package com.shubhamtambi27.seat_reservation.api;

import com.shubhamtambi27.seat_reservation.api.dto.CreateShowRequest;
import com.shubhamtambi27.seat_reservation.api.dto.ReservationResponse;
import com.shubhamtambi27.seat_reservation.api.dto.ReserveRequest;
import com.shubhamtambi27.seat_reservation.api.dto.ShowResponse;
import com.shubhamtambi27.seat_reservation.auth.Actor;
import com.shubhamtambi27.seat_reservation.auth.AuthFilter;
import com.shubhamtambi27.seat_reservation.service.ReservationService;
import com.shubhamtambi27.seat_reservation.service.ShowService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class SeatReservationController {

    private final ShowService shows;
    private final ReservationService reservations;

    public SeatReservationController(ShowService shows, ReservationService reservations) {
        this.shows = shows;
        this.reservations = reservations;
    }

    @PostMapping("/shows")
    @ResponseStatus(HttpStatus.CREATED)
    public ShowResponse createShow(@RequestBody CreateShowRequest request) {
        return shows.create(request);
    }

    @GetMapping("/shows/{id}")
    public ShowResponse getShow(@PathVariable UUID id) {
        return shows.get(id);
    }

    @PostMapping("/shows/{id}/reserve")
    @ResponseStatus(HttpStatus.CREATED)
    public ReservationResponse reserve(
            @PathVariable UUID id,
            @RequestBody(required = false) ReserveRequest request,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            HttpServletRequest httpRequest) {
        Actor actor = (Actor) httpRequest.getAttribute(AuthFilter.ACTOR_ATTR);
        return reservations.reserve(id, actor.id(), request, idempotencyKey);
    }

    @PostMapping("/reservations/{id}/cancel")
    public ReservationResponse cancel(@PathVariable UUID id, HttpServletRequest httpRequest) {
        Actor actor = (Actor) httpRequest.getAttribute(AuthFilter.ACTOR_ATTR);
        return reservations.cancel(id, actor.id());
    }
}
