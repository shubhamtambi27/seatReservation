# WRITEUP

## Atomic decision

The system of record is the `seats` table, one row per `(show_id, seat_label)`. A reservation never does read-then-write in the application.

Inside a single Postgres transaction the reserve path:

1. Inserts/locks the idempotency row (`FOR UPDATE`).
2. Takes `pg_advisory_xact_lock(hashtext(show_id), hashtext(user_id))` so one user cannot race their own limit.
3. `SELECT … FROM seats WHERE show_id = ? AND seat_label IN (…) ORDER BY seat_label FOR UPDATE`.
4. Declines if any row is missing or not `available`, or if `confirmed_count + requested > per_user_limit`.
5. Inserts the reservation and `UPDATE seats SET status = 'confirmed' WHERE status = 'available'`.

That `FOR UPDATE` plus the `status = 'available'` guard is why a hot seat has exactly one winner. The second transaction waits, then sees `confirmed` and returns `409 seat_taken` — not a 500, and not a second copy of A12.

**Multi-seat / deadlock:** all-or-nothing. Seats are always locked in `seat_label` ascending order, so concurrent `["A12","A13"]` and `["A13","A12"]` take the same lock order. The advisory lock is taken *before* seat locks, and is keyed only by `(show, user)`, so two different users skip each other’s advisory lock and still join on seat rows in label order. No lock cycle.

A released seat is `UPDATE seats SET status = 'available', reservation_id = NULL WHERE reservation_id = :id AND status = 'confirmed'`. If those rows already belong to someone else, the `WHERE` matches nothing.

## Idempotency

Stored in `idempotency_keys` with primary key `(show_id, user_id, idempotency_key)` and a `request_fingerprint` (sorted seat labels joined by comma).

Exactly-once: the first writer `INSERT … ON CONFLICT DO NOTHING`, then both writers `SELECT … FOR UPDATE`. The loser blocks until the winner commits. If `reservation_id` is set and the fingerprint matches, the original reservation is returned (`201`, metric `reservations_idempotent_replay_total`). If the fingerprint differs → `409 idempotency_mismatch`. The key is scoped to the token’s user, so another user with the same key is a different row.

After cancel, a retry of the same key still returns that original reservation (`cancelled`) and does not sell again. A new booking needs a new key.

## Holds and expiry

Chosen model: **confirm on reserve**, explicit **cancel** by the owner. There is no TTL hold in this version, so `held` is always zero and the reconciliation invariant is `available + 0 + confirmed = total_seats`. Cancel is idempotent for the owner and forbidden for everyone else.

## Consistency vs availability under a partition

This service chooses **consistency**. If Postgres is unreachable, `/health/ready` fails closed (`503`) and reserve/cancel cannot invent a sale. During a partition between app and DB there is no AP “best effort hold”: better to refuse than to double-sell. Multiple app instances are fine as long as they share the same Postgres — the locks live in the database, not in process memory.

## Observability (what pages at 2am)

- **Ready probe failing** — DB is gone or rejecting connections. Stop serving traffic.
- **`reservations_declined_total{reason="seat_taken"}` spike at on-sale** — expected; a **`http_5xx` spike** or `reservations_confirmed_total` growing faster than unique confirmed seats is not.
- **`seats_available + seats_held + seats_confirmed` ≠ row count** — invariant broken; page immediately.
- **Sustained `declined_busy` / 429** — pool or lock timeouts; scale connections/instances, do not loosen the atomic update.
- Structured JSON logs on stdout with `request_id` / `user_id` for a single buyer’s trace. Tail the host logs (Render/Railway log stream, or `docker compose logs -f app`).

## AI usage

Directed, not decided.

- **I specified** the product: correctness bar, all-or-nothing partials, confirm-then-cancel, Postgres as the single atomic decision point, Prometheus metrics, burst script, Docker, this write-up’s questions.
- **Cursor (Composer / Auto)** wrote the Spring Boot 4 + JDBC implementation, Flyway schema, tests, Docker/compose, `burst.py`, README, and this file, then iterated on compile/test failures.
- **I own** the locking story (`FOR UPDATE` in label order, advisory lock for the per-user cap, idempotency row as the retry mutex). If asked in the interview to add paid holds, webhooks, or a second region, that extension should come from this design, not from regenerating the service.

## What I’d do next

- Time-boxed `held` with a confirm/payment step and a sweeper that only expires rows still `held` by that reservation.
- Seat inventory gauges labeled by `show_id`, and an alert on invariant mismatch.
- PgBouncer transaction pooling carefully (advisory locks are session/xact scoped).
- Load-shed with `429` before the Tomcat queue, always requiring the same idempotency key on retry.
- A public repo with incremental commits and a deployed URL on Render/Fly with log drain attached.
