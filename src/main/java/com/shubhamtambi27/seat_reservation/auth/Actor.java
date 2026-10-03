package com.shubhamtambi27.seat_reservation.auth;

public record Actor(Kind kind, String id) {

    public enum Kind {
        ADMIN,
        USER
    }

    public static Actor admin() {
        return new Actor(Kind.ADMIN, "admin");
    }

    public static Actor user(String userId) {
        return new Actor(Kind.USER, userId);
    }

    public boolean isAdmin() {
        return kind == Kind.ADMIN;
    }

    public boolean isUser() {
        return kind == Kind.USER;
    }
}
