package com.shubhamtambi27.seat_reservation.repo;

import com.shubhamtambi27.seat_reservation.domain.Show;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class ShowRepository {

    private final JdbcTemplate jdbc;

    public ShowRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(Show show) {
        jdbc.update(
                """
                INSERT INTO shows (id, name, price_paise, per_user_limit, created_at)
                VALUES (?, ?, ?, ?, ?)
                """,
                show.id(),
                show.name(),
                show.pricePaise(),
                show.perUserLimit(),
                show.createdAt());
    }

    public Optional<Show> findById(UUID id) {
        List<Show> rows = jdbc.query("SELECT id, name, price_paise, per_user_limit, created_at FROM shows WHERE id = ?",
                this::map, id);
        return rows.stream().findFirst();
    }

    private Show map(ResultSet rs, int rowNum) throws SQLException {
        return new Show(
                rs.getObject("id", UUID.class),
                rs.getString("name"),
                rs.getLong("price_paise"),
                rs.getInt("per_user_limit"),
                rs.getObject("created_at", java.time.OffsetDateTime.class));
    }
}
