package com.shubhamtambi27.seat_reservation.auth;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ActorTest {

    @Test
    void adminAndUserAreDistinct() {
        assertThat(Actor.admin().isAdmin()).isTrue();
        assertThat(Actor.admin().isUser()).isFalse();
        assertThat(Actor.user("alice").isUser()).isTrue();
        assertThat(Actor.user("alice").isAdmin()).isFalse();
        assertThat(Actor.user("alice").id()).isEqualTo("alice");
    }
}
