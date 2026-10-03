package com.shubhamtambi27.seat_reservation.service;

import com.shubhamtambi27.seat_reservation.api.dto.CreateShowRequest;
import com.shubhamtambi27.seat_reservation.api.dto.SeatView;
import com.shubhamtambi27.seat_reservation.api.dto.ShowResponse;
import com.shubhamtambi27.seat_reservation.api.error.ApiException;
import com.shubhamtambi27.seat_reservation.domain.Seat;
import com.shubhamtambi27.seat_reservation.domain.Show;
import com.shubhamtambi27.seat_reservation.repo.SeatRepository;
import com.shubhamtambi27.seat_reservation.repo.ShowRepository;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ShowService {

    private final ShowRepository shows;
    private final SeatRepository seats;

    public ShowService(ShowRepository shows, SeatRepository seats) {
        this.shows = shows;
        this.seats = seats;
    }

    @Transactional
    public ShowResponse create(CreateShowRequest request) {
        if (request == null || request.name() == null || request.name().isBlank()) {
            throw ApiException.badRequest("invalid_name", "name is required");
        }
        if (request.price_paise() == null || request.price_paise() < 0) {
            throw ApiException.badRequest("invalid_price", "price_paise must be a non-negative integer");
        }
        int limit = request.per_user_limit() == null ? 4 : request.per_user_limit();
        if (limit <= 0) {
            throw ApiException.badRequest("invalid_limit", "per_user_limit must be positive");
        }
        List<String> labels = normalizeSeats(request.seats());
        Show show = new Show(UUID.randomUUID(), request.name().trim(), request.price_paise(), limit, OffsetDateTime.now(ZoneOffset.UTC));
        shows.insert(show);
        seats.insertAll(show.id(), labels);
        return toResponse(show, seats.findAll(show.id()));
    }

    @Transactional(readOnly = true)
    public ShowResponse get(UUID id) {
        Show show = shows.findById(id).orElseThrow(() -> ApiException.notFound("show_not_found", "Show does not exist"));
        return toResponse(show, seats.findAll(id));
    }

    public static ShowResponse toResponse(Show show, List<Seat> seatRows) {
        int available = 0;
        int held = 0;
        int confirmed = 0;
        List<SeatView> views = new ArrayList<>(seatRows.size());
        for (Seat seat : seatRows) {
            switch (seat.status()) {
                case "available" -> available++;
                case "held" -> held++;
                case "confirmed" -> confirmed++;
                default -> {
                }
            }
            views.add(new SeatView(seat.label(), seat.status()));
        }
        return new ShowResponse(
                show.id(),
                show.name(),
                show.pricePaise(),
                show.perUserLimit(),
                seatRows.size(),
                available,
                held,
                confirmed,
                views);
    }

    static List<String> normalizeSeats(List<String> raw) {
        if (raw == null || raw.isEmpty()) {
            throw ApiException.badRequest("invalid_seats", "at least one seat is required");
        }
        List<String> labels = new ArrayList<>(raw.size());
        Set<String> seen = new HashSet<>();
        for (String seat : raw) {
            if (seat == null || seat.isBlank()) {
                throw ApiException.badRequest("invalid_seats", "seat labels must be non-empty");
            }
            String label = seat.trim();
            if (!seen.add(label)) {
                throw ApiException.badRequest("duplicate_seats", "request contains duplicate seat " + label);
            }
            labels.add(label);
        }
        labels.sort(String::compareTo);
        return labels;
    }
}
