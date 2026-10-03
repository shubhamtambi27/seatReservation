package com.shubhamtambi27.seat_reservation.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    @Bean
    OpenAPI seatReservationOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("Seat Reservation")
                        .version("1.0")
                        .description("""
                                Assigned-seat booking API. Identity is the Bearer token, never a body field.
                                Admin token creates shows. Any other token string is the user id.
                                Multi-seat reserves are all-or-nothing. Money is integer paise.
                                """))
                .components(new Components()
                        .addSecuritySchemes(
                                "bearerAuth",
                                new SecurityScheme()
                                        .type(SecurityScheme.Type.HTTP)
                                        .scheme("bearer")
                                        .bearerFormat("token")
                                        .description(
                                                "Admin: value of ADMIN_TOKEN (default admin-secret). "
                                                        + "User: any [A-Za-z0-9._:-]+ string; that string is the user_id.")));
    }
}
