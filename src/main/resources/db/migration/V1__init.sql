CREATE TABLE shows (
    id UUID PRIMARY KEY,
    name TEXT NOT NULL,
    price_paise BIGINT NOT NULL CHECK (price_paise >= 0),
    per_user_limit INT NOT NULL CHECK (per_user_limit > 0),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE reservations (
    id UUID PRIMARY KEY,
    show_id UUID NOT NULL REFERENCES shows (id),
    user_id TEXT NOT NULL,
    amount_paise BIGINT NOT NULL CHECK (amount_paise >= 0),
    status TEXT NOT NULL CHECK (status IN ('confirmed', 'cancelled')),
    idempotency_key TEXT NOT NULL,
    seats TEXT[] NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE seats (
    show_id UUID NOT NULL REFERENCES shows (id),
    seat_label TEXT NOT NULL,
    status TEXT NOT NULL CHECK (status IN ('available', 'held', 'confirmed')),
    reservation_id UUID REFERENCES reservations (id),
    PRIMARY KEY (show_id, seat_label)
);

CREATE TABLE idempotency_keys (
    show_id UUID NOT NULL,
    user_id TEXT NOT NULL,
    idempotency_key TEXT NOT NULL,
    request_fingerprint TEXT NOT NULL,
    reservation_id UUID REFERENCES reservations (id),
    PRIMARY KEY (show_id, user_id, idempotency_key)
);

CREATE INDEX idx_seats_reservation ON seats (reservation_id);
CREATE INDEX idx_seats_show_status ON seats (show_id, status);
CREATE INDEX idx_reservations_user_show ON reservations (show_id, user_id) WHERE status = 'confirmed';
