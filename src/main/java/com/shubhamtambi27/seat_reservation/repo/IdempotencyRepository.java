package com.shubhamtambi27.seat_reservation.repo;

import com.shubhamtambi27.seat_reservation.domain.IdempotencyRecord;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class IdempotencyRepository {

    private final JdbcTemplate jdbc;

    public IdempotencyRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void insertIgnore(UUID showId, String userId, String key, String fingerprint) {
        jdbc.update(
                """
                INSERT INTO idempotency_keys (show_id, user_id, idempotency_key, request_fingerprint)
                VALUES (?, ?, ?, ?)
                ON CONFLICT (show_id, user_id, idempotency_key) DO NOTHING
                """,
                showId,
                userId,
                key,
                fingerprint);
    }

    public Optional<IdempotencyRecord> lock(UUID showId, String userId, String key) {
        List<IdempotencyRecord> rows = jdbc.query(
                """
                SELECT request_fingerprint, reservation_id
                FROM idempotency_keys
                WHERE show_id = ? AND user_id = ? AND idempotency_key = ?
                FOR UPDATE
                """,
                (rs, i) -> new IdempotencyRecord(
                        rs.getString("request_fingerprint"),
                        rs.getObject("reservation_id", UUID.class)),
                showId,
                userId,
                key);
        return rows.stream().findFirst();
    }

    public void attachReservation(UUID showId, String userId, String key, UUID reservationId) {
        jdbc.update(
                """
                UPDATE idempotency_keys
                SET reservation_id = ?
                WHERE show_id = ? AND user_id = ? AND idempotency_key = ?
                """,
                reservationId,
                showId,
                userId,
                key);
    }
}
