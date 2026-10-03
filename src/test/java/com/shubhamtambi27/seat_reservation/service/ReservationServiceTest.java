package com.shubhamtambi27.seat_reservation.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

@ExtendWith(MockitoExtension.class)
class ReservationServiceTest {

    @Mock
    private ShowRepository shows;
    @Mock
    private SeatRepository seats;
    @Mock
    private ReservationRepository reservations;
    @Mock
    private IdempotencyRepository idempotency;
    @Mock
    private LockRepository locks;
    @Mock
    private ReservationMetrics metrics;
    @Mock
    private TransactionTemplate transactions;

    private ReservationService service;
    private final UUID showId = UUID.randomUUID();
    private final Show show = new Show(showId, "friday-night", 25000, 4, OffsetDateTime.now());

    @BeforeEach
    void setUp() {
        lenient().when(transactions.execute(any())).thenAnswer(invocation -> {
            TransactionCallback<?> callback = invocation.getArgument(0);
            return callback.doInTransaction(mock(TransactionStatus.class));
        });
        service = new ReservationService(shows, seats, reservations, idempotency, locks, metrics, transactions);
    }

    @Test
    void reserveConfirmsAvailableSeatAndChargesIntegerPaise() {
        when(shows.findById(showId)).thenReturn(Optional.of(show));
        when(idempotency.lock(eq(showId), eq("alice"), eq("k1")))
                .thenReturn(Optional.of(new IdempotencyRecord("A12", null)));
        when(seats.lockInOrder(showId, List.of("A12")))
                .thenReturn(List.of(new Seat("A12", "available", null)));
        when(seats.countConfirmedForUser(showId, "alice")).thenReturn(0);
        when(seats.confirmSeats(eq(showId), eq(List.of("A12")), any())).thenReturn(1);

        ReservationResponse response = service.reserve(
                showId, "alice", new ReserveRequest(List.of("A12"), "k1"), null);

        assertThat(response.status()).isEqualTo("confirmed");
        assertThat(response.user_id()).isEqualTo("alice");
        assertThat(response.amount_paise()).isEqualTo(25000);
        ArgumentCaptor<Reservation> captor = ArgumentCaptor.forClass(Reservation.class);
        verify(reservations).insert(captor.capture());
        assertThat(captor.getValue().amountPaise()).isEqualTo(25000L);
        verify(metrics).confirmed();
        verify(locks).lockUserShow(showId.toString(), "alice");
    }

    @Test
    void reserveIsAllOrNothingWhenAnySeatTaken() {
        when(shows.findById(showId)).thenReturn(Optional.of(show));
        when(idempotency.lock(any(), any(), any()))
                .thenReturn(Optional.of(new IdempotencyRecord("A12,A13", null)));
        when(seats.lockInOrder(showId, List.of("A12", "A13"))).thenReturn(List.of(
                new Seat("A12", "confirmed", UUID.randomUUID()),
                new Seat("A13", "available", null)));

        assertThatThrownBy(() -> service.reserve(
                        showId, "bob", new ReserveRequest(List.of("A13", "A12"), "k"), null))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> {
                    ApiException api = (ApiException) ex;
                    assertThat(api.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(api.getError()).isEqualTo("seat_taken");
                });
        verify(reservations, never()).insert(any());
        verify(metrics).declined("seat_taken");
    }

    @Test
    void reserveEnforcesPerUserLimit() {
        when(shows.findById(showId)).thenReturn(Optional.of(show));
        when(idempotency.lock(any(), any(), any()))
                .thenReturn(Optional.of(new IdempotencyRecord("A1", null)));
        when(seats.lockInOrder(showId, List.of("A1")))
                .thenReturn(List.of(new Seat("A1", "available", null)));
        when(seats.countConfirmedForUser(showId, "alice")).thenReturn(4);

        assertThatThrownBy(() -> service.reserve(
                        showId, "alice", new ReserveRequest(List.of("A1"), "k"), null))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).getError())
                .isEqualTo("per_user_limit");
        verify(metrics).declined("per_user_limit");
    }

    @Test
    void sameKeySameSeatsReplaysOriginalReservation() {
        UUID reservationId = UUID.randomUUID();
        Reservation existing = new Reservation(
                reservationId, showId, "alice", 25000, "confirmed", "k1", List.of("A12"), OffsetDateTime.now());
        when(shows.findById(showId)).thenReturn(Optional.of(show));
        when(idempotency.lock(showId, "alice", "k1"))
                .thenReturn(Optional.of(new IdempotencyRecord("A12", reservationId)));
        when(reservations.findById(reservationId)).thenReturn(Optional.of(existing));

        ReservationResponse first = service.reserve(showId, "alice", new ReserveRequest(List.of("A12"), "k1"), null);
        assertThat(first.reservation_id()).isEqualTo(reservationId);
        verify(metrics).idempotentReplay();
        verify(seats, never()).lockInOrder(any(), any());
    }

    @Test
    void sameKeyDifferentSeatsConflicts() {
        when(shows.findById(showId)).thenReturn(Optional.of(show));
        when(idempotency.lock(showId, "alice", "k1"))
                .thenReturn(Optional.of(new IdempotencyRecord("A12", UUID.randomUUID())));

        assertThatThrownBy(() -> service.reserve(
                        showId, "alice", new ReserveRequest(List.of("A13"), "k1"), null))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).getError())
                .isEqualTo("idempotency_mismatch");
        verify(metrics).declined("idempotency_mismatch");
    }

    @Test
    void unknownSeatIsBadRequest() {
        when(shows.findById(showId)).thenReturn(Optional.of(show));
        when(idempotency.lock(any(), any(), any()))
                .thenReturn(Optional.of(new IdempotencyRecord("ZZ", null)));
        when(seats.lockInOrder(showId, List.of("ZZ"))).thenReturn(List.of());

        assertThatThrownBy(() -> service.reserve(
                        showId, "alice", new ReserveRequest(List.of("ZZ"), "k"), null))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> {
                    ApiException api = (ApiException) ex;
                    assertThat(api.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(api.getError()).isEqualTo("unknown_seat");
                });
    }

    @Test
    void missingIdempotencyKeyIsBadRequest() {
        assertThatThrownBy(() -> service.reserve(showId, "alice", new ReserveRequest(List.of("A1"), "  "), null))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).getError())
                .isEqualTo("missing_idempotency_key");
    }

    @Test
    void headerIdempotencyKeyWinsOverBody() {
        when(shows.findById(showId)).thenReturn(Optional.of(show));
        when(idempotency.lock(showId, "alice", "from-header"))
                .thenReturn(Optional.of(new IdempotencyRecord("A1", null)));
        when(seats.lockInOrder(showId, List.of("A1")))
                .thenReturn(List.of(new Seat("A1", "available", null)));
        when(seats.countConfirmedForUser(showId, "alice")).thenReturn(0);
        when(seats.confirmSeats(any(), any(), any())).thenReturn(1);

        service.reserve(showId, "alice", new ReserveRequest(List.of("A1"), "from-body"), "from-header");
        verify(idempotency).insertIgnore(showId, "alice", "from-header", "A1");
    }

    @Test
    void cancelIsOwnerOnlyAndReleasesSeats() {
        UUID reservationId = UUID.randomUUID();
        Reservation reservation = new Reservation(
                reservationId, showId, "erin", 25000, "confirmed", "e1", List.of("A5"), OffsetDateTime.now());
        when(reservations.findById(reservationId)).thenReturn(Optional.of(reservation));

        ReservationResponse cancelled = service.cancel(reservationId, "erin");
        assertThat(cancelled.status()).isEqualTo("cancelled");
        verify(reservations).cancelIfOwner(reservationId, "erin");
        verify(seats).releaseForReservation(reservationId);
        verify(metrics).cancelled();

        assertThatThrownBy(() -> service.cancel(reservationId, "mallory"))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).getStatus()).isEqualTo(HttpStatus.FORBIDDEN));
    }

    @Test
    void cancelUnknownReservationIs404() {
        UUID id = UUID.randomUUID();
        when(reservations.findById(id)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.cancel(id, "erin"))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).getError())
                .isEqualTo("reservation_not_found");
    }

    @Test
    void unknownShowIs404() {
        when(shows.findById(showId)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.reserve(showId, "alice", new ReserveRequest(List.of("A1"), "k"), null))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).getError())
                .isEqualTo("show_not_found");
    }
}
