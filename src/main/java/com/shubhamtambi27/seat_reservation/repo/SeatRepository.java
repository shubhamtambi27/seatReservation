package com.shubhamtambi27.seat_reservation.repo;

import com.shubhamtambi27.seat_reservation.domain.Seat;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class SeatRepository {

    private final JdbcTemplate jdbc;

    public SeatRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void insertAll(UUID showId, List<String> labels) {
        jdbc.batchUpdate(
                "INSERT INTO seats (show_id, seat_label, status) VALUES (?, ?, 'available')",
                labels,
                500,
                (PreparedStatement ps, String label) -> {
                    ps.setObject(1, showId);
                    ps.setString(2, label);
                });
    }

    public List<Seat> findAll(UUID showId) {
        return jdbc.query(
                """
                SELECT seat_label, status, reservation_id
                FROM seats
                WHERE show_id = ?
                ORDER BY seat_label
                """,
                this::map,
                showId);
    }

    /**
     * Locks the requested seats in label order so multi-seat transactions cannot deadlock.
     */
    public List<Seat> lockInOrder(UUID showId, List<String> labels) {
        String placeholders = String.join(",", labels.stream().map(s -> "?").toList());
        Object[] args = new Object[labels.size() + 1];
        args[0] = showId;
        for (int i = 0; i < labels.size(); i++) {
            args[i + 1] = labels.get(i);
        }
        return jdbc.query(
                """
                SELECT seat_label, status, reservation_id
                FROM seats
                WHERE show_id = ? AND seat_label IN (""" + placeholders + """
                )
                ORDER BY seat_label
                FOR UPDATE
                """,
                this::map,
                args);
    }

    public int confirmSeats(UUID showId, List<String> labels, UUID reservationId) {
        String placeholders = String.join(",", labels.stream().map(s -> "?").toList());
        Object[] args = new Object[labels.size() + 2];
        args[0] = reservationId;
        args[1] = showId;
        for (int i = 0; i < labels.size(); i++) {
            args[i + 2] = labels.get(i);
        }
        return jdbc.update(
                """
                UPDATE seats
                SET status = 'confirmed', reservation_id = ?
                WHERE show_id = ? AND status = 'available' AND seat_label IN (""" + placeholders + ")",
                args);
    }

    public int releaseForReservation(UUID reservationId) {
        return jdbc.update(
                """
                UPDATE seats
                SET status = 'available', reservation_id = NULL
                WHERE reservation_id = ? AND status = 'confirmed'
                """,
                reservationId);
    }

    public int countConfirmedForUser(UUID showId, String userId) {
        Integer count = jdbc.queryForObject(
                """
                SELECT COUNT(*)
                FROM seats s
                JOIN reservations r ON r.id = s.reservation_id
                WHERE s.show_id = ?
                  AND r.user_id = ?
                  AND s.status = 'confirmed'
                  AND r.status = 'confirmed'
                """,
                Integer.class,
                showId,
                userId);
        return count == null ? 0 : count;
    }

    private Seat map(ResultSet rs, int rowNum) throws SQLException {
        return new Seat(
                rs.getString("seat_label"),
                rs.getString("status"),
                rs.getObject("reservation_id", UUID.class));
    }
}
