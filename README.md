# Seat Reservation at Scale

JSON HTTP API that sells assigned seats without double-booking, without exceeding a per-user limit, and without double-charging retried requests. A seat is claimed in one PostgreSQL transaction with row locks taken in a deterministic order.

Partial multi-seat requests are **all-or-nothing**: if any named seat is missing or not `available`, the whole request is declined with `409` and reason `seat_taken`. No subset is held.

Holds are **confirmed immediately**. `POST /reservations/{id}/cancel` (owner only) returns those seats to `available`. Cancel updates only rows still owned by that reservation, so it cannot resurrect a seat already confirmed to someone else.

## Run locally

```bash
docker compose up --build
# wait until GET http://localhost:8080/health/ready returns 200
./burst.sh http://localhost:8080
```

Postgres only (app on the host):

```bash
docker compose up -d db
export JDBC_DATABASE_URL=jdbc:postgresql://localhost:5432/seats
export DB_USER=seats DB_PASSWORD=seats ADMIN_TOKEN=admin-secret
./mvnw spring-boot:run
```

Tests (Docker required for Testcontainers):

```bash
./mvnw test
```

## Auth

Identity is taken only from `Authorization: Bearer <token>`. Any `user_id` in the JSON body is ignored.

| Token | Role |
| --- | --- |
| `ADMIN_TOKEN` (default `admin-secret`) | create shows |
| any other `[A-Za-z0-9._:-]+` string | that string **is** the user id |

## API

### Create a show — `POST /shows` (admin)

```json
{ "name": "friday-night", "seats": ["A1", "A2", "A12"], "price_paise": 25000, "per_user_limit": 4 }
```

`201` returns the show with every seat `available`. `per_user_limit` defaults to `4`. Money is integer paise.

### Reserve — `POST /shows/{id}/reserve` (user)

Header `Idempotency-Key` or body field `idempotency_key`:

```json
{ "seats": ["A12"], "idempotency_key": "req-1" }
```

`201`:

```json
{
  "reservation_id": "…",
  "show_id": "…",
  "user_id": "…",
  "seats": ["A12"],
  "amount_paise": 25000,
  "status": "confirmed"
}
```

| Outcome | HTTP | `error` |
| --- | --- | --- |
| winner | 201 | — |
| seat already held/sold, or all-or-nothing miss | 409 | `seat_taken` |
| would exceed per-user limit | 409 | `per_user_limit` |
| same key, different seats | 409 | `idempotency_mismatch` |
| same key, same seats (retry) | 201 | original reservation, including after cancel |
| unknown seat label | 400 | `unknown_seat` |

### Cancel — `POST /reservations/{id}/cancel` (owning user)

Returns the reservation with `status: cancelled`. A second cancel is a no-op. Another user’s token gets `403`.

### Show state — `GET /shows/{id}` (public)

Per-seat `available` / `held` / `confirmed` plus counts. Invariant: `available + held + confirmed == total_seats`. This model confirms immediately, so `held` stays `0` unless you later add time-boxed holds.

### Health and metrics

| Endpoint | Meaning |
| --- | --- |
| `GET /health/live` | process is up |
| `GET /health/ready` | Postgres `isValid`; **fails closed** (`503`) if not |
| `GET /actuator/health/liveness` | Spring liveness |
| `GET /actuator/health/readiness` | Spring readiness including `db` |
| `GET /actuator/prometheus` | Prometheus scrape |

Metrics (names as scraped):

- `reservations_confirmed_total`
- `reservations_declined_total{reason="seat_taken|per_user_limit|idempotency_mismatch"}`
- `reservations_idempotent_replay_total`
- `seats_available`, `seats_held`, `seats_confirmed` (gauges over all shows)

Logs are JSON on stdout with `request_id` (from `X-Request-Id` or generated) and `user_id`. The same `X-Request-Id` is echoed on the response.

## Burst

```bash
./burst.sh <BASE_URL>
# or
python3 burst.py <BASE_URL> --hot 500 --users 100
```

The script creates a fresh show, storms one hot seat with many users, mixes other seats, hammers one user past the limit of 4, retries an idempotency key (match + mismatch), then prints the outcome distribution and checks reconciliation.

## Deploy

The image is a multi-stage Docker build. On Render/Railway/Fly:

1. Provision Postgres.
2. Set `DATABASE_URL` (`postgres://…` is accepted) or `JDBC_DATABASE_URL`.
3. Set `ADMIN_TOKEN`.
4. Bind `PORT` (already read as `server.port`).

Live URL (fill in after deploy): **TBD**

## Design in one paragraph

The atomic decision is `SELECT … FROM seats WHERE show_id = ? AND seat_label IN (…) ORDER BY seat_label FOR UPDATE` followed by `UPDATE … SET status = 'confirmed' WHERE status = 'available'`. Two transactions racing A12 serialize on that row; the loser sees non-`available` and returns `409`. Multi-seat requests lock labels in sorted order to avoid deadlock. Per-user limit uses `pg_advisory_xact_lock(hashtext(show_id), hashtext(user_id))` so parallel reserves for the same user cannot both pass the count check. Idempotency is a primary key on `(show_id, user_id, idempotency_key)` locked with `FOR UPDATE` so retries wait for the first writer and then replay or `409` on fingerprint mismatch.
