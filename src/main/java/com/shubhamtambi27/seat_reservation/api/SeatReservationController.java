package com.shubhamtambi27.seat_reservation.api;

import com.shubhamtambi27.seat_reservation.api.dto.CreateShowRequest;
import com.shubhamtambi27.seat_reservation.api.dto.ErrorResponse;
import com.shubhamtambi27.seat_reservation.api.dto.ReservationResponse;
import com.shubhamtambi27.seat_reservation.api.dto.ReserveRequest;
import com.shubhamtambi27.seat_reservation.api.dto.ShowResponse;
import com.shubhamtambi27.seat_reservation.auth.Actor;
import com.shubhamtambi27.seat_reservation.auth.AuthFilter;
import com.shubhamtambi27.seat_reservation.service.ReservationService;
import com.shubhamtambi27.seat_reservation.service.ShowService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.headers.Header;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
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
@Tag(name = "Shows & reservations")
public class SeatReservationController {

    private final ShowService shows;
    private final ReservationService reservations;

    public SeatReservationController(ShowService shows, ReservationService reservations) {
        this.shows = shows;
        this.reservations = reservations;
    }

    @PostMapping("/shows")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Create a show", description = "Admin only. Every seat starts as available.")
    @SecurityRequirement(name = "bearerAuth")
    @ApiResponses({
        @ApiResponse(responseCode = "201", description = "Show created"),
        @ApiResponse(
                responseCode = "400",
                description = "Invalid payload",
                content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
        @ApiResponse(
                responseCode = "401",
                description = "Missing or invalid token",
                content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
        @ApiResponse(
                responseCode = "403",
                description = "Not an admin token",
                content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    public ShowResponse createShow(@RequestBody CreateShowRequest request) {
        return shows.create(request);
    }

    @GetMapping("/shows/{id}")
    @Operation(summary = "Show state", description = "Public. available + held + confirmed == total_seats.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Current inventory"),
        @ApiResponse(
                responseCode = "404",
                description = "Unknown show",
                content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    public ShowResponse getShow(@PathVariable UUID id) {
        return shows.get(id);
    }

    @PostMapping("/shows/{id}/reserve")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(
            summary = "Reserve seats",
            description = """
                    User token is the buyer. Same Idempotency-Key retries return the original reservation.
                    Different seats on the same key → 409. Partial lists are all-or-nothing.
                    """)
    @SecurityRequirement(name = "bearerAuth")
    @ApiResponses({
        @ApiResponse(
                responseCode = "201",
                description = "Confirmed, or idempotent replay of the original reservation",
                headers = @Header(name = "X-Request-Id", description = "Correlation id")),
        @ApiResponse(
                responseCode = "400",
                description = "Validation error (missing key, unknown seat, …)",
                content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
        @ApiResponse(
                responseCode = "401",
                description = "Missing user token",
                content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
        @ApiResponse(
                responseCode = "409",
                description = "seat_taken, per_user_limit, or idempotency_mismatch",
                content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    public ReservationResponse reserve(
            @PathVariable UUID id,
            @RequestBody(required = false) ReserveRequest request,
            @Parameter(
                    in = ParameterIn.HEADER,
                    name = "Idempotency-Key",
                    description = "Optional if idempotency_key is in the body")
                    @RequestHeader(value = "Idempotency-Key", required = false)
                    String idempotencyKey,
            @Parameter(hidden = true) HttpServletRequest httpRequest) {
        Actor actor = (Actor) httpRequest.getAttribute(AuthFilter.ACTOR_ATTR);
        return reservations.reserve(id, actor.id(), request, idempotencyKey);
    }

    @PostMapping("/reservations/{id}/cancel")
    @Operation(summary = "Cancel a reservation", description = "Owner only. Idempotent. Seats become available again.")
    @SecurityRequirement(name = "bearerAuth")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Cancelled (or already cancelled)"),
        @ApiResponse(
                responseCode = "401",
                description = "Missing user token",
                content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
        @ApiResponse(
                responseCode = "403",
                description = "Not the owner",
                content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
        @ApiResponse(
                responseCode = "404",
                description = "Unknown reservation",
                content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    public ReservationResponse cancel(
            @PathVariable UUID id, @Parameter(hidden = true) HttpServletRequest httpRequest) {
        Actor actor = (Actor) httpRequest.getAttribute(AuthFilter.ACTOR_ATTR);
        return reservations.cancel(id, actor.id());
    }
}
