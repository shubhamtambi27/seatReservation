package com.shubhamtambi27.seat_reservation.auth;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
@Order(1)
public class AuthFilter extends OncePerRequestFilter {

    public static final String ACTOR_ATTR = "actor";

    private final String adminToken;

    public AuthFilter(@Value("${app.admin-token}") String adminToken) {
        this.adminToken = adminToken;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        String method = request.getMethod();
        if (HttpMethod.OPTIONS.matches(method)) {
            return true;
        }
        if (path.startsWith("/health/") || path.startsWith("/actuator")) {
            return true;
        }
        if (HttpMethod.GET.matches(method) && path.startsWith("/shows")) {
            return true;
        }
        return false;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        String header = request.getHeader("Authorization");
        if (header == null || !header.startsWith("Bearer ")) {
            unauthorized(response, "missing_bearer_token");
            return;
        }
        String token = header.substring("Bearer ".length()).trim();
        if (token.isEmpty() || token.length() > 128) {
            unauthorized(response, "invalid_token");
            return;
        }
        Actor actor;
        if (token.equals(adminToken)) {
            actor = Actor.admin();
        } else if (token.matches("[A-Za-z0-9._:\\-]+")) {
            actor = Actor.user(token);
        } else {
            unauthorized(response, "invalid_token");
            return;
        }

        if (isAdminOnly(request) && !actor.isAdmin()) {
            forbidden(response, "admin_required");
            return;
        }
        if (isUserOnly(request) && !actor.isUser()) {
            unauthorized(response, "user_token_required");
            return;
        }

        request.setAttribute(ACTOR_ATTR, actor);
        MDC.put("user_id", actor.id());
        filterChain.doFilter(request, response);
    }

    private static boolean isAdminOnly(HttpServletRequest request) {
        return HttpMethod.POST.matches(request.getMethod()) && "/shows".equals(request.getRequestURI());
    }

    private static boolean isUserOnly(HttpServletRequest request) {
        String path = request.getRequestURI();
        String method = request.getMethod();
        return HttpMethod.POST.matches(method)
                && (path.matches("/shows/[^/]+/reserve") || path.matches("/reservations/[^/]+/cancel"));
    }

    private static void unauthorized(HttpServletResponse response, String error) throws IOException {
        write(response, HttpServletResponse.SC_UNAUTHORIZED, error, "Authentication required");
    }

    private static void forbidden(HttpServletResponse response, String error) throws IOException {
        write(response, HttpServletResponse.SC_FORBIDDEN, error, "Insufficient permissions");
    }

    private static void write(HttpServletResponse response, int status, String error, String message) throws IOException {
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.getWriter().write("{\"error\":\"" + error + "\",\"message\":\"" + message + "\"}");
    }
}
