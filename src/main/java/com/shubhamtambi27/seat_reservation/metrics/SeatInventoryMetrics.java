package com.shubhamtambi27.seat_reservation.metrics;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class SeatInventoryMetrics {

    private final JdbcTemplate jdbc;
    private final Map<String, Double> last = new ConcurrentHashMap<>();
    private volatile long refreshedAt;

    public SeatInventoryMetrics(JdbcTemplate jdbc, MeterRegistry registry) {
        this.jdbc = jdbc;
        last.put("available", 0.0);
        last.put("held", 0.0);
        last.put("confirmed", 0.0);
        Gauge.builder("seats_available", this, metrics -> metrics.read("available")).register(registry);
        Gauge.builder("seats_held", this, metrics -> metrics.read("held")).register(registry);
        Gauge.builder("seats_confirmed", this, metrics -> metrics.read("confirmed")).register(registry);
    }

    private double read(String status) {
        refresh();
        return last.getOrDefault(status, 0.0);
    }

    private void refresh() {
        long now = System.currentTimeMillis();
        if (now - refreshedAt < 500 && refreshedAt != 0) {
            return;
        }
        synchronized (this) {
            if (now - refreshedAt < 500 && refreshedAt != 0) {
                return;
            }
            last.put("available", 0.0);
            last.put("held", 0.0);
            last.put("confirmed", 0.0);
            jdbc.query(
                    "SELECT status, COUNT(*) AS n FROM seats GROUP BY status",
                    (org.springframework.jdbc.core.RowCallbackHandler) rs ->
                            last.put(rs.getString("status"), rs.getDouble("n")));
            refreshedAt = now;
        }
    }
}
