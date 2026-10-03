package com.shubhamtambi27.seat_reservation.api;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.sql.Connection;
import java.util.Map;
import javax.sql.DataSource;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Tag(name = "Health")
public class HealthController {

    private final DataSource dataSource;

    public HealthController(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @GetMapping("/health/live")
    @Operation(summary = "Liveness", description = "Process is up. Does not check the database.")
    public Map<String, String> live() {
        return Map.of("status", "ok");
    }

    @GetMapping("/health/ready")
    @Operation(summary = "Readiness", description = "Fails closed (503) if Postgres is unreachable.")
    public ResponseEntity<Map<String, String>> ready() {
        try (Connection connection = dataSource.getConnection()) {
            if (connection.isValid(2)) {
                return ResponseEntity.ok(Map.of("status", "ok"));
            }
        } catch (Exception ignored) {
            // fail closed
        }
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(Map.of("status", "unready"));
    }
}
