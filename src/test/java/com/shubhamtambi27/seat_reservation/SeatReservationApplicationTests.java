package com.shubhamtambi27.seat_reservation;

import static org.assertj.core.api.Assertions.assertThat;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class SeatReservationApplicationTests {

    private static EmbeddedPostgres postgres;

    @BeforeAll
    static void startPostgres() throws Exception {
        postgres = EmbeddedPostgres.builder().start();
    }

    @DynamicPropertySource
    static void datasourceProps(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> postgres.getJdbcUrl("postgres", "postgres"));
        registry.add("spring.datasource.username", () -> "postgres");
        registry.add("spring.datasource.password", () -> "postgres");
    }

    @LocalServerPort
    int port;

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final JsonMapper mapper = JsonMapper.builder().build();
    private String showId;

    @BeforeEach
    void createShow() throws Exception {
        HttpResponse<String> created = post(
                "/shows",
                "admin-secret",
                Map.of(
                        "name", "friday-night",
                        "seats", List.of("A1", "A2", "A3", "A4", "A5", "A6", "A7", "A8", "A9", "A10", "A12", "A13"),
                        "price_paise", 25000,
                        "per_user_limit", 4));
        assertThat(created.statusCode()).isEqualTo(201);
        JsonNode body = mapper.readTree(created.body());
        showId = body.get("id").asText();
        assertThat(body.get("available").asInt() + body.get("held").asInt() + body.get("confirmed").asInt())
                .isEqualTo(body.get("total_seats").asInt());
    }

    @Test
    void hotSeatHasExactlyOneWinner() throws Exception {
        int n = 80;
        Outcome outcome = storm(n, i -> reserve("user-" + i, List.of("A12"), "k-" + i));
        assertThat(outcome.confirmed).isEqualTo(1);
        assertThat(outcome.conflict).isEqualTo(n - 1);
        assertThat(outcome.serverError).isZero();
        assertReconciled();
        assertThat(statusOf("A12")).isEqualTo("confirmed");
    }

    @Test
    void perUserLimitHoldsUnderConcurrency() throws Exception {
        int n = 10;
        Outcome outcome = storm(n, i -> reserve("alice", List.of("A" + (i + 1)), "alice-" + i));
        assertThat(outcome.confirmed).isEqualTo(4);
        assertThat(outcome.conflict).isEqualTo(6);
        assertThat(outcome.serverError).isZero();
        assertReconciled();
    }

    @Test
    void idempotentRetryReturnsOriginalAndMismatchConflicts() throws Exception {
        HttpResponse<String> first = reserve("bob", List.of("A1"), "same-key");
        HttpResponse<String> replay = reserve("bob", List.of("A1"), "same-key");
        HttpResponse<String> mismatch = reserve("bob", List.of("A2"), "same-key");

        assertThat(first.statusCode()).isEqualTo(201);
        assertThat(replay.statusCode()).isEqualTo(201);
        assertThat(mapper.readTree(replay.body()).get("reservation_id").asText())
                .isEqualTo(mapper.readTree(first.body()).get("reservation_id").asText());
        assertThat(mismatch.statusCode()).isEqualTo(409);
        assertReconciled();
    }

    @Test
    void multiSeatIsAllOrNothing() throws Exception {
        assertThat(reserve("carol", List.of("A12"), "c1").statusCode()).isEqualTo(201);
        assertThat(reserve("dave", List.of("A12", "A13"), "d1").statusCode()).isEqualTo(409);
        assertThat(statusOf("A13")).isEqualTo("available");
        assertThat(statusOf("A12")).isEqualTo("confirmed");
        assertReconciled();
    }

    @Test
    void cancelReleasesSeatAndDoesNotResurrectOthers() throws Exception {
        JsonNode held = mapper.readTree(reserve("erin", List.of("A5"), "e1").body());
        String reservationId = held.get("reservation_id").asText();

        HttpResponse<String> cancelled = post("/reservations/" + reservationId + "/cancel", "erin", Map.of());
        assertThat(cancelled.statusCode()).isEqualTo(200);
        assertThat(mapper.readTree(cancelled.body()).get("status").asText()).isEqualTo("cancelled");

        HttpResponse<String> spoofed = post(
                "/reservations/" + reservationId + "/cancel",
                "mallory",
                Map.of("user_id", "erin"));
        assertThat(spoofed.statusCode()).isEqualTo(403);

        HttpResponse<String> rebook = reserve("frank", List.of("A5"), "f1");
        assertThat(rebook.statusCode()).isEqualTo(201);
        assertThat(mapper.readTree(rebook.body()).get("user_id").asText()).isEqualTo("frank");
        assertReconciled();
    }

    @Test
    void identityComesFromTokenNotBody() throws Exception {
        HttpResponse<String> response = post(
                "/shows/" + showId + "/reserve",
                "real-user",
                Map.of(
                        "seats", List.of("A3"),
                        "idempotency_key", "spoof",
                        "user_id", "someone-else"));
        assertThat(response.statusCode()).isEqualTo(201);
        assertThat(mapper.readTree(response.body()).get("user_id").asText()).isEqualTo("real-user");
    }

    @Test
    void healthAndMetricsExist() throws Exception {
        assertThat(get("/health/live").statusCode()).isEqualTo(200);
        assertThat(get("/health/ready").statusCode()).isEqualTo(200);
        String metrics = get("/actuator/prometheus").body();
        assertThat(metrics).contains("reservations_confirmed_total");
        assertThat(metrics).contains("seats_available");
    }

    @Test
    void swaggerIsPublic() throws Exception {
        assertThat(get("/v3/api-docs").statusCode()).isEqualTo(200);
        String spec = get("/v3/api-docs").body();
        assertThat(spec).contains("Seat Reservation");
        assertThat(spec).contains("/shows/{id}/reserve");
        assertThat(get("/swagger-ui.html").statusCode()).isIn(200, 302);
        assertThat(get("/swagger-ui/index.html").statusCode()).isEqualTo(200);
    }

    @Test
    void createShowRequiresAdminToken() throws Exception {
        HttpResponse<String> forbidden = post(
                "/shows",
                "not-admin",
                Map.of("name", "x", "seats", List.of("A1"), "price_paise", 1));
        assertThat(forbidden.statusCode()).isEqualTo(403);

        HttpRequest missing = HttpRequest.newBuilder(URI.create(base() + "/shows"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"name\":\"x\",\"seats\":[\"A1\"],\"price_paise\":1}"))
                .build();
        assertThat(http.send(missing, HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(401);
    }

    @Test
    void reserveValidatesSeatsAndIdempotency() throws Exception {
        HttpResponse<String> unknown = reserve("user", List.of("NOPE"), "k-unknown");
        assertThat(unknown.statusCode()).isEqualTo(400);
        assertThat(mapper.readTree(unknown.body()).get("error").asText()).isEqualTo("unknown_seat");

        HttpResponse<String> missingKey = post(
                "/shows/" + showId + "/reserve",
                "user",
                Map.of("seats", List.of("A1")));
        assertThat(missingKey.statusCode()).isEqualTo(400);

        HttpResponse<String> twoSeats = reserve("gina", List.of("A1", "A2"), "two");
        assertThat(twoSeats.statusCode()).isEqualTo(201);
        assertThat(mapper.readTree(twoSeats.body()).get("amount_paise").asLong()).isEqualTo(50000);
        assertReconciled();
    }

    @Test
    void unknownShowAndReservationAre404() throws Exception {
        UUID missing = UUID.randomUUID();
        assertThat(get("/shows/" + missing).statusCode()).isEqualTo(404);
        HttpResponse<String> cancel = post("/reservations/" + missing + "/cancel", "erin", Map.of());
        assertThat(cancel.statusCode()).isEqualTo(404);
    }

    private Outcome storm(int n, RequestFactory factory) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(n);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(n);
        AtomicInteger confirmed = new AtomicInteger();
        AtomicInteger conflict = new AtomicInteger();
        AtomicInteger serverError = new AtomicInteger();
        ConcurrentLinkedQueue<Integer> extra = new ConcurrentLinkedQueue<>();
        for (int i = 0; i < n; i++) {
            int idx = i;
            pool.submit(() -> {
                try {
                    start.await();
                    int status = factory.send(idx).statusCode();
                    if (status >= 500) {
                        serverError.incrementAndGet();
                    } else if (status == 201) {
                        confirmed.incrementAndGet();
                    } else if (status == 409) {
                        conflict.incrementAndGet();
                    } else {
                        extra.add(status);
                    }
                } catch (Exception ex) {
                    serverError.incrementAndGet();
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        assertThat(done.await(60, TimeUnit.SECONDS)).isTrue();
        pool.shutdownNow();
        assertThat(extra).isEmpty();
        return new Outcome(confirmed.get(), conflict.get(), serverError.get());
    }

    private HttpResponse<String> reserve(String user, List<String> seats, String key) throws Exception {
        return post("/shows/" + showId + "/reserve", user, Map.of("seats", seats, "idempotency_key", key));
    }

    private void assertReconciled() throws Exception {
        JsonNode show = mapper.readTree(get("/shows/" + showId).body());
        assertThat(show.get("available").asInt() + show.get("held").asInt() + show.get("confirmed").asInt())
                .isEqualTo(show.get("total_seats").asInt());
    }

    private String statusOf(String label) throws Exception {
        JsonNode show = mapper.readTree(get("/shows/" + showId).body());
        for (JsonNode seat : show.get("seats")) {
            if (label.equals(seat.get("label").asText())) {
                return seat.get("status").asText();
            }
        }
        throw new IllegalStateException("missing seat " + label);
    }

    private HttpResponse<String> get(String path) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(base() + path)).GET().build();
        return http.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> post(String path, String token, Map<String, Object> body) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(base() + path))
                .header("Authorization", "Bearer " + token)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)))
                .build();
        return http.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private String base() {
        return "http://127.0.0.1:" + port;
    }

    @FunctionalInterface
    private interface RequestFactory {
        HttpResponse<String> send(int i) throws Exception;
    }

    private record Outcome(int confirmed, int conflict, int serverError) {
    }
}
