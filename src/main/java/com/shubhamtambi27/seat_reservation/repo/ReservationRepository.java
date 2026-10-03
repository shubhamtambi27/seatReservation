package com.shubhamtambi27.seat_reservation.repo;

import com.shubhamtambi27.seat_reservation.domain.Reservation;
import java.sql.Array;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class ReservationRepository {

    private final JdbcTemplate jdbc;

    public ReservationRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(Reservation reservation) {
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    """
                    INSERT INTO reservations
                      (id, show_id, user_id, amount_paise, status, idempotency_key, seats, created_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                    """);
            ps.setObject(1, reservation.id());
            ps.setObject(2, reservation.showId());
            ps.setString(3, reservation.userId());
            ps.setLong(4, reservation.amountPaise());
            ps.setString(5, reservation.status());
            ps.setString(6, reservation.idempotencyKey());
            ps.setArray(7, con.createArrayOf("text", reservation.seats().toArray()));
            ps.setObject(8, reservation.createdAt());
            return ps;
        });
    }

    public Optional<Reservation> findById(UUID id) {
        List<Reservation> rows = jdbc.query(
                """
                SELECT id, show_id, user_id, amount_paise, status, idempotency_key, seats, created_at
                FROM reservations WHERE id = ?
                """,
                this::map,
                id);
        return rows.stream().findFirst();
    }

    public int cancelIfOwner(UUID id, String userId) {
        return jdbc.update(
                """
                UPDATE reservations
                SET status = 'cancelled'
                WHERE id = ? AND user_id = ? AND status = 'confirmed'
                """,
                id,
                userId);
    }

    private Reservation map(ResultSet rs, int rowNum) throws SQLException {
        Array array = rs.getArray("seats");
        String[] seats = array == null ? new String[0] : (String[]) array.getArray();
        return new Reservation(
                rs.getObject("id", UUID.class),
                rs.getObject("show_id", UUID.class),
                rs.getString("user_id"),
                rs.getLong("amount_paise"),
                rs.getString("status"),
                rs.getString("idempotency_key"),
                Arrays.asList(seats),
                rs.getObject("created_at", java.time.OffsetDateTime.class));
    }
}
