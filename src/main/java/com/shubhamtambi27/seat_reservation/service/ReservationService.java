package com.shubhamtambi27.seat_reservation.service;

import com.shubhamtambi27.seat_reservation.api.dto.ReservationResponse;
import com.shubhamtambi27.seat_reservation.api.dto.ReserveRequest;
import com.shubhamtambi27.seat_reservation.api.error.ApiException;
import com.shubhamtambi27.seat_reservation.domain.IdempotencyRecord;
import com.shubhamtambi27.seat_reservation.domain.Reservation;
import com.shubhamtambi27.seat_reservation.domain.Seat;
import com.shubhamtambi27.seat_reservation.domain.Show;
import com.shubhamtambi27.seat_reservation.metrics.ReservationMetrics;
import com.shubhamtambi27.seat_reservation.repo.IdempotencyRepository;
import com.shubhamtambi27.seat_reservation.repo.LockRepository;
import com.shubhamtambi27.seat_reservation.repo.ReservationRepository;
import com.shubhamtambi27.seat_reservation.repo.SeatRepository;
import com.shubhamtambi27.seat_reservation.repo.ShowRepository;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class ReservationService {

    private static final Logger log = LoggerFactory.getLogger(ReservationService.class);
    private static final int MAX_RETRIES = 4;

    private final ShowRepository shows;
    private final SeatRepository seats;
    private final ReservationRepository reservations;
    private final IdempotencyRepository idempotency;
    private final LockRepository locks;
    private final ReservationMetrics metrics;
    private final TransactionTemplate transactions;

    public ReservationService(
            ShowRepository shows,
            SeatRepository seats,
            ReservationRepository reservations,
            IdempotencyRepository idempotency,
            LockRepository locks,
            ReservationMetrics metrics,
            TransactionTemplate transactions) {
        this.shows = shows;
        this.seats = seats;
        this.reservations = reservations;
        this.idempotency = idempotency;
        this.locks = locks;
        this.metrics = metrics;
        this.transactions = transactions;
    }

    public ReservationResponse reserve(UUID showId, String userId, ReserveRequest request, String idempotencyHeader) {
        List<String> wanted = ShowService.normalizeSeats(request == null ? null : request.seats());
        String key = resolveIdempotencyKey(idempotencyHeader, request);
        String fingerprint = String.join(",", wanted);

        int attempt = 0;
        while (true) {
            try {
                return transactions.execute(status -> doReserve(showId, userId, wanted, key, fingerprint));
            } catch (ApiException ex) {
                recordDecline(ex.getError());
                throw ex;
            } catch (DataAccessException ex) {
                if (isRetryable(ex) && attempt++ < MAX_RETRIES) {
                    log.warn("retrying_serialization_failure attempt={}", attempt);
                    continue;
                }
                if (isBusy(ex)) {
                    throw ApiException.tooManyRequests("busy", "Database is busy; retry with the same idempotency key");
                }
                throw ex;
            }
        }
    }

    public ReservationResponse cancel(UUID reservationId, String userId) {
        try {
            return transactions.execute(status -> doCancel(reservationId, userId));
        } catch (ApiException ex) {
            throw ex;
        } catch (DataAccessException ex) {
            if (isBusy(ex)) {
                throw ApiException.tooManyRequests("busy", "Database is busy; retry the cancel");
            }
            throw ex;
        }
    }

    private ReservationResponse doReserve(UUID showId, String userId, List<String> wanted, String key, String fingerprint) {
        Show show = shows.findById(showId).orElseThrow(() -> ApiException.notFound("show_not_found", "Show does not exist"));

        idempotency.insertIgnore(showId, userId, key, fingerprint);
        IdempotencyRecord record = idempotency.lock(showId, userId, key)
                .orElseThrow(() -> ApiException.conflict("idempotency_mismatch", "Idempotency key could not be claimed"));

        if (!fingerprint.equals(record.requestFingerprint())) {
            throw ApiException.conflict("idempotency_mismatch", "Idempotency key was already used with a different seat list");
        }
        if (record.reservationId() != null) {
            Reservation existing = reservations.findById(record.reservationId())
                    .orElseThrow(() -> ApiException.conflict("idempotency_mismatch", "Idempotency key is bound to a missing reservation"));
            afterCommit(metrics::idempotentReplay);
            log.info("reserve_replay reservation_id={} show_id={} user_id={}", existing.id(), showId, userId);
            return toResponse(existing);
        }

        locks.lockUserShow(showId.toString(), userId);

        List<Seat> locked = seats.lockInOrder(showId, wanted);
        if (locked.size() != wanted.size()) {
            throw ApiException.badRequest("unknown_seat", "One or more seats do not exist for this show");
        }
        boolean taken = locked.stream().anyMatch(seat -> !"available".equals(seat.status()));
        if (taken) {
            throw ApiException.conflict("seat_taken", "One or more requested seats are not available");
        }

        int alreadyHeld = seats.countConfirmedForUser(showId, userId);
        if (alreadyHeld + wanted.size() > show.perUserLimit()) {
            throw ApiException.conflict("per_user_limit", "Reservation would exceed per-user seat limit of " + show.perUserLimit());
        }

        Reservation reservation = new Reservation(
                UUID.randomUUID(),
                showId,
                userId,
                Math.multiplyExact(show.pricePaise(), wanted.size()),
                "confirmed",
                key,
                wanted,
                OffsetDateTime.now(ZoneOffset.UTC));
        reservations.insert(reservation);
        int updated = seats.confirmSeats(showId, wanted, reservation.id());
        if (updated != wanted.size()) {
            throw ApiException.conflict("seat_taken", "One or more requested seats are not available");
        }
        idempotency.attachReservation(showId, userId, key, reservation.id());
        afterCommit(metrics::confirmed);
        log.info(
                "reserve_confirmed reservation_id={} show_id={} user_id={} seats={} amount_paise={}",
                reservation.id(),
                showId,
                userId,
                wanted,
                reservation.amountPaise());
        return toResponse(reservation);
    }

    private ReservationResponse doCancel(UUID reservationId, String userId) {
        Reservation reservation = reservations.findById(reservationId)
                .orElseThrow(() -> ApiException.notFound("reservation_not_found", "Reservation does not exist"));
        if (!reservation.userId().equals(userId)) {
            throw ApiException.forbidden("not_owner", "Only the owning user can cancel this reservation");
        }
        if ("cancelled".equals(reservation.status())) {
            return toResponse(reservation);
        }
        locks.lockUserShow(reservation.showId().toString(), userId);
        reservations.cancelIfOwner(reservationId, userId);
        seats.releaseForReservation(reservationId);
        afterCommit(metrics::cancelled);
        log.info("reserve_cancelled reservation_id={} show_id={} user_id={}", reservationId, reservation.showId(), userId);
        return new ReservationResponse(
                reservation.id(),
                reservation.showId(),
                reservation.userId(),
                reservation.seats(),
                reservation.amountPaise(),
                "cancelled");
    }

    private void recordDecline(String error) {
        switch (error) {
            case "seat_taken" -> metrics.declined("seat_taken");
            case "per_user_limit" -> metrics.declined("per_user_limit");
            case "idempotency_mismatch" -> metrics.declined("idempotency_mismatch");
            default -> {
            }
        }
    }

    private static String resolveIdempotencyKey(String header, ReserveRequest request) {
        String key = header != null && !header.isBlank() ? header.trim() : null;
        if (key == null && request != null && request.idempotency_key() != null && !request.idempotency_key().isBlank()) {
            key = request.idempotency_key().trim();
        }
        if (key == null || key.isBlank()) {
            throw ApiException.badRequest("missing_idempotency_key", "Idempotency-Key header or idempotency_key is required");
        }
        if (key.length() > 128) {
            throw ApiException.badRequest("invalid_idempotency_key", "Idempotency key is too long");
        }
        return key;
    }

    private static ReservationResponse toResponse(Reservation reservation) {
        return new ReservationResponse(
                reservation.id(),
                reservation.showId(),
                reservation.userId(),
                reservation.seats(),
                reservation.amountPaise(),
                reservation.status());
    }

    private static void afterCommit(Runnable action) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    action.run();
                }
            });
        } else {
            action.run();
        }
    }

    private static boolean isRetryable(DataAccessException ex) {
        SQLException sql = findSql(ex);
        if (sql == null) {
            return false;
        }
        String state = sql.getSQLState();
        return "40001".equals(state) || "40P01".equals(state);
    }

    private static boolean isBusy(DataAccessException ex) {
        SQLException sql = findSql(ex);
        if (sql == null) {
            return false;
        }
        String state = sql.getSQLState();
        return "55P03".equals(state) || "57014".equals(state) || "53300".equals(state);
    }

    private static SQLException findSql(Throwable ex) {
        Throwable current = ex;
        while (current != null) {
            if (current instanceof SQLException sql) {
                return sql;
            }
            current = current.getCause();
        }
        return null;
    }
}
