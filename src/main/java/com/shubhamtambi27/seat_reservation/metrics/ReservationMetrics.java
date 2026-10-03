package com.shubhamtambi27.seat_reservation.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
public class ReservationMetrics {

    private final Counter confirmed;
    private final Counter replay;
    private final Counter cancelled;
    private final MeterRegistry registry;

    public ReservationMetrics(MeterRegistry registry) {
        this.registry = registry;
        this.confirmed = Counter.builder("reservations.confirmed").register(registry);
        this.replay = Counter.builder("reservations.idempotent.replay").register(registry);
        this.cancelled = Counter.builder("reservations.cancelled").register(registry);
        for (String reason : List.of("seat_taken", "per_user_limit", "idempotency_mismatch")) {
            Counter.builder("reservations.declined").tag("reason", reason).register(registry);
        }
    }

    public void confirmed() {
        confirmed.increment();
    }

    public void declined(String reason) {
        Counter.builder("reservations.declined")
                .tag("reason", reason)
                .register(registry)
                .increment();
    }

    public void idempotentReplay() {
        replay.increment();
    }

    public void cancelled() {
        cancelled.increment();
    }
}
