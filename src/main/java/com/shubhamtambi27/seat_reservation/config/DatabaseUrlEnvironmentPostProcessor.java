package com.shubhamtambi27.seat_reservation.config;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

/**
 * Translates Heroku/Render/Railway {@code DATABASE_URL} (postgres://…) into Spring JDBC properties.
 */
public class DatabaseUrlEnvironmentPostProcessor implements EnvironmentPostProcessor {

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        String databaseUrl = firstNonBlank(
                environment.getProperty("DATABASE_URL"),
                environment.getProperty("database.url"));
        if (databaseUrl == null || databaseUrl.startsWith("jdbc:")) {
            return;
        }
        if (!databaseUrl.startsWith("postgres://") && !databaseUrl.startsWith("postgresql://")) {
            return;
        }
        URI uri = URI.create(databaseUrl.replaceFirst("^postgres(ql)?://", "http://"));
        String userInfo = uri.getUserInfo();
        if (userInfo == null) {
            return;
        }
        String[] parts = userInfo.split(":", 2);
        String user = urlDecode(parts[0]);
        String password = parts.length > 1 ? urlDecode(parts[1]) : "";
        int port = uri.getPort() == -1 ? 5432 : uri.getPort();
        String jdbc = "jdbc:postgresql://" + uri.getHost() + ":" + port + uri.getPath();
        if (uri.getQuery() != null && !uri.getQuery().isBlank()) {
            jdbc += "?" + uri.getQuery();
        }
        Map<String, Object> props = new HashMap<>();
        props.put("spring.datasource.url", jdbc);
        props.put("spring.datasource.username", user);
        props.put("spring.datasource.password", password);
        environment.getPropertySources().addFirst(new MapPropertySource("databaseUrl", props));
    }

    private static String firstNonBlank(String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    private static String urlDecode(String value) {
        return URLDecoder.decode(value, StandardCharsets.UTF_8);
    }
}
