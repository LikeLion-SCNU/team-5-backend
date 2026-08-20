package org.example.naeilbank.auth;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.Map;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Testcontainers
@ActiveProfiles("integration-test")
@AutoConfigureMockMvc
@SpringBootTest(properties = {
        "spring.jpa.hibernate.ddl-auto=none",
        "app.auth.rate-limit.capacity=50",
        "app.auth.rate-limit.window=1m"
})
class AuthSecurityIntegrationTest extends AuthIntegrationSupport {
    @Test
    void authorizationRolesValidationCorsRateLimitAndMethodContractsHold() throws Exception {
        UUID userId = UUID.randomUUID();
        UUID adminId = UUID.randomUUID();
        jdbcTemplate.update(
                "insert into users (id, email, auth_provider, role) values (?, ?, 'email', 'USER')",
                userId,
                "user-" + UUID.randomUUID() + "@example.com"
        );
        jdbcTemplate.update(
                "insert into users (id, email, auth_provider, role) values (?, ?, 'email', 'ADMIN')",
                adminId,
                "admin-" + UUID.randomUUID() + "@example.com"
        );

        String userAccessToken = jwtTokenProvider.createToken(userId, "user@example.com", "USER");
        String adminAccessToken = jwtTokenProvider.createToken(adminId, "admin@example.com", "ADMIN");

        mockMvc.perform(get("/api/v1/auth/me").header("Authorization", "Bearer " + userAccessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("ROLE_USER"));
        mockMvc.perform(get("/api/admin/probe").header("Authorization", "Bearer " + userAccessToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
        mockMvc.perform(get("/api/admin/probe").header("Authorization", "Bearer " + adminAccessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ok"));
        mockMvc.perform(get("/api/v1/auth/me").header("Authorization", "Bearer malformed"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_ACCESS_TOKEN"));

        mockMvc.perform(post("/api/v1/auth/join")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Correlation-Id", "auth-validation-test")
                        .content(objectMapper.writeValueAsString(Map.of("email", "bad", "password", "short"))))
                .andExpect(status().isBadRequest())
                .andExpect(header().string("X-Correlation-Id", "auth-validation-test"))
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        mockMvc.perform(options("/api/v1/auth/login")
                        .header("Origin", "http://localhost:5173")
                        .header("Access-Control-Request-Method", "POST"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", "http://localhost:5173"));
        mockMvc.perform(get("/api/v1/auth/login"))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.code").value("METHOD_NOT_ALLOWED"));
        mockMvc.perform(get("/api/v1/auth/join"))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.code").value("METHOD_NOT_ALLOWED"));

        for (int i = 0; i < 50; i++) {
            mockMvc.perform(post("/api/v1/auth/login")
                            .with(request -> {
                                request.setRemoteAddr("198.51.100.77");
                                return request;
                            })
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(Map.of(
                                    "email", "rate-" + i + "@example.com",
                                    "password", "password-123"
                            ))))
                    .andExpect(status().isUnauthorized());
        }
        mockMvc.perform(post("/api/v1/auth/login")
                        .with(request -> {
                            request.setRemoteAddr("198.51.100.77");
                            request.addHeader("X-Forwarded-For", "203.0.113.1");
                            return request;
                        })
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "email", "rate-final@example.com",
                                "password", "password-123"
                        ))))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("TOO_MANY_REQUESTS"));
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class AdminProbeConfiguration {
        @Bean
        AdminProbeController adminProbeController() {
            return new AdminProbeController();
        }
    }

    @RestController
    static class AdminProbeController {
        @GetMapping("/api/admin/probe")
        Map<String, String> probe() {
            return Map.of("status", "ok");
        }
    }
}
