package com.shubhamtambi27.seat_reservation.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.mock.env.MockEnvironment;

class DatabaseUrlEnvironmentPostProcessorTest {

    private final DatabaseUrlEnvironmentPostProcessor processor = new DatabaseUrlEnvironmentPostProcessor();

    @Test
    void convertsPostgresUrlToJdbcProperties() {
        MockEnvironment env = new MockEnvironment();
        env.setProperty("DATABASE_URL", "postgres://user:p%40ss@db.example:5433/seats?sslmode=require");

        processor.postProcessEnvironment(env, mock(SpringApplication.class));

        assertThat(env.getProperty("spring.datasource.url"))
                .isEqualTo("jdbc:postgresql://db.example:5433/seats?sslmode=require");
        assertThat(env.getProperty("spring.datasource.username")).isEqualTo("user");
        assertThat(env.getProperty("spring.datasource.password")).isEqualTo("p@ss");
    }

    @Test
    void ignoresJdbcUrlsAndMissingValues() {
        MockEnvironment jdbc = new MockEnvironment();
        jdbc.setProperty("DATABASE_URL", "jdbc:postgresql://localhost/seats");
        processor.postProcessEnvironment(jdbc, mock(SpringApplication.class));
        assertThat(jdbc.getProperty("spring.datasource.url")).isNull();

        MockEnvironment empty = new MockEnvironment();
        processor.postProcessEnvironment(empty, mock(SpringApplication.class));
        assertThat(empty.getProperty("spring.datasource.url")).isNull();
    }
}
