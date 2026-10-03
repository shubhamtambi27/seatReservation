package com.shubhamtambi27.seat_reservation.repo;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class LockRepository {

    private final JdbcTemplate jdbc;

    public LockRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void lockUserShow(String showId, String userId) {
        jdbc.queryForList("SELECT pg_advisory_xact_lock(hashtext(?), hashtext(?))", showId, userId);
    }
}
