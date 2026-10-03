package com.shubhamtambi27.seat_reservation.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.shubhamtambi27.seat_reservation.api.dto.CreateShowRequest;
import com.shubhamtambi27.seat_reservation.api.dto.ShowResponse;
import com.shubhamtambi27.seat_reservation.api.error.ApiException;
import com.shubhamtambi27.seat_reservation.domain.Seat;
import com.shubhamtambi27.seat_reservation.domain.Show;
import com.shubhamtambi27.seat_reservation.repo.SeatRepository;
import com.shubhamtambi27.seat_reservation.repo.ShowRepository;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

@ExtendWith(MockitoExtension.class)
class ShowServiceTest {

    @Mock
    private ShowRepository shows;

    @Mock
    private SeatRepository seats;

    @InjectMocks
    private ShowService service;

    @Test
    void createPersistsShowAndAvailableSeats() {
        when(seats.findAll(any())).thenAnswer(invocation -> {
            UUID id = invocation.getArgument(0);
            return List.of(
                    new Seat("A1", "available", null),
                    new Seat("A2", "available", null));
        });

        ShowResponse response = service.create(new CreateShowRequest(" friday-night ", List.of("A2", "A1"), 25000L, null));

        ArgumentCaptor<Show> showCaptor = ArgumentCaptor.forClass(Show.class);
        verify(shows).insert(showCaptor.capture());
        Show stored = showCaptor.getValue();
        assertThat(stored.name()).isEqualTo("friday-night");
        assertThat(stored.pricePaise()).isEqualTo(25000L);
        assertThat(stored.perUserLimit()).isEqualTo(4);
        verify(seats).insertAll(eq(stored.id()), eq(List.of("A1", "A2")));
        assertThat(response.available() + response.held() + response.confirmed()).isEqualTo(response.total_seats());
        assertThat(response.available()).isEqualTo(2);
    }

    @Test
    void createRejectsBlankNameNegativePriceAndBadLimit() {
        assertThatThrownBy(() -> service.create(new CreateShowRequest("  ", List.of("A1"), 1L, 4)))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).getError())
                .isEqualTo("invalid_name");
        assertThatThrownBy(() -> service.create(new CreateShowRequest("n", List.of("A1"), -1L, 4)))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).getStatus()).isEqualTo(HttpStatus.BAD_REQUEST));
        assertThatThrownBy(() -> service.create(new CreateShowRequest("n", List.of("A1"), 1L, 0)))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).getError())
                .isEqualTo("invalid_limit");
    }

    @Test
    void getUnknownShowIs404() {
        UUID id = UUID.randomUUID();
        when(shows.findById(id)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.get(id))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> {
                    ApiException api = (ApiException) ex;
                    assertThat(api.getStatus()).isEqualTo(HttpStatus.NOT_FOUND);
                    assertThat(api.getError()).isEqualTo("show_not_found");
                });
    }

    @Test
    void normalizeSeatsSortsTrimsAndRejectsDuplicates() {
        assertThat(ShowService.normalizeSeats(List.of(" B2 ", "A1"))).containsExactly("A1", "B2");
        assertThatThrownBy(() -> ShowService.normalizeSeats(List.of("A1", "A1")))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).getError())
                .isEqualTo("duplicate_seats");
        assertThatThrownBy(() -> ShowService.normalizeSeats(List.of()))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).getError())
                .isEqualTo("invalid_seats");
    }

    @Test
    void toResponseCountsStatusesForReconciliation() {
        Show show = new Show(UUID.randomUUID(), "n", 100, 4, OffsetDateTime.now());
        ShowResponse response = ShowService.toResponse(show, List.of(
                new Seat("A1", "available", null),
                new Seat("A2", "held", UUID.randomUUID()),
                new Seat("A3", "confirmed", UUID.randomUUID())));
        assertThat(response.available()).isEqualTo(1);
        assertThat(response.held()).isEqualTo(1);
        assertThat(response.confirmed()).isEqualTo(1);
        assertThat(response.available() + response.held() + response.confirmed()).isEqualTo(3);
    }
}
