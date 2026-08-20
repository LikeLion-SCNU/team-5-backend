package org.example.naeilbank.auth;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
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
class AuthApiIntegrationTest extends AuthIntegrationSupport {
    @Test
    void signupLoginRotationReplayAndLogoutPersistExpectedRefreshTokenState() throws Exception {
        assertAuthSchemaMetadataMatchesRepositoryContract();
        String email = "flow-" + UUID.randomUUID() + "@example.com";

        JsonNode join = objectMapper.readTree(mockMvc.perform(post("/api/v1/auth/join")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "email", email,
                                "password", "password-123",
                                "name", "통합테스트"
                        ))))
                .andExpect(status().isCreated())
                .andExpect(header().exists("X-Correlation-Id"))
                .andReturn().getResponse().getContentAsString());
        UUID userId = UUID.fromString(join.get("userId").asText());

        Map<String, Object> userRow = jdbcTemplate.queryForMap(
                "select email, password_hash, auth_provider, role from users where id = ?",
                userId
        );
        assertThat(userRow.get("email")).isEqualTo(email);
        assertThat(userRow.get("password_hash")).isNotEqualTo("password-123");
        assertThat(userRow.get("auth_provider")).isEqualTo("email");
        assertThat(userRow.get("role")).isEqualTo("USER");

        JsonNode login = login(email, "password-123");
        String firstRefresh = login.get("refreshToken").asText();
        assertThat(login.get("accessToken").asText()).isNotBlank();
        assertThat(login.at("/user/role").asText()).isEqualTo("ROLE_USER");
        assertThat(login.get("expiresIn").asLong()).isEqualTo(1800);

        String firstHash = refreshTokenHasher.hash(firstRefresh);
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from refresh_tokens where token_hash = ?",
                Integer.class,
                firstHash
        )).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from refresh_tokens where token_hash = ?",
                Integer.class,
                firstRefresh
        )).isZero();

        JsonNode rotated = objectMapper.readTree(refresh(firstRefresh, status().isOk())
                .andReturn().getResponse().getContentAsString());
        String secondRefresh = rotated.get("refreshToken").asText();
        String secondHash = refreshTokenHasher.hash(secondRefresh);

        Map<String, Object> firstTokenRow = jdbcTemplate.queryForMap(
                "select family_id, used_at, revoked_at from refresh_tokens where token_hash = ?",
                firstHash
        );
        Map<String, Object> secondTokenRow = jdbcTemplate.queryForMap(
                "select family_id, previous_token_hash, revoked_at from refresh_tokens where token_hash = ?",
                secondHash
        );
        assertThat(firstTokenRow.get("used_at")).isNotNull();
        assertThat(firstTokenRow.get("revoked_at")).isNotNull();
        assertThat(secondTokenRow.get("previous_token_hash")).isEqualTo(firstHash);
        assertThat(secondTokenRow.get("family_id")).isEqualTo(firstTokenRow.get("family_id"));
        assertThat(secondTokenRow.get("revoked_at")).isNull();

        refresh(firstRefresh, status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("REFRESH_TOKEN_REUSED"));
        assertThat(jdbcTemplate.queryForObject(
                "select reuse_detected_at is not null from refresh_tokens where token_hash = ?",
                Boolean.class,
                firstHash
        )).isTrue();
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from refresh_tokens where family_id = ? and revoked_at is null",
                Integer.class,
                firstTokenRow.get("family_id")
        )).isZero();

        String logoutRefresh = login(email, "password-123").get("refreshToken").asText();
        mockMvc.perform(post("/api/v1/auth/logout")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("refreshToken", logoutRefresh))))
                .andExpect(status().isNoContent());
        refresh(logoutRefresh, status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("REFRESH_TOKEN_REUSED"));
    }

    @Test
    void malformedExpiredAndCredentialFailuresUseSpecificAuthErrors() throws Exception {
        String email = "failure-" + UUID.randomUUID() + "@example.com";
        UUID userId = UUID.randomUUID();
        jdbcTemplate.update("""
                        insert into users (id, email, password_hash, auth_provider, role)
                        values (?, ?, ?, 'email', 'USER')
                        """,
                userId,
                email,
                passwordEncoder.encode("password-123")
        );

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "email", email,
                                "password", "wrong-password"
                        ))))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"));
        refresh("not a token", status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_REFRESH_TOKEN"));

        String expiredRawToken = "expiredRefreshTokenValue_000000000000000000000000";
        jdbcTemplate.update("""
                        insert into refresh_tokens (user_id, token_hash, family_id, expires_at)
                        values (?, ?, ?, ?)
                        """,
                userId,
                refreshTokenHasher.hash(expiredRawToken),
                UUID.randomUUID(),
                OffsetDateTime.parse("2020-01-01T00:00:00Z")
        );
        refresh(expiredRawToken, status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("REFRESH_TOKEN_EXPIRED"));
        assertThat(jdbcTemplate.queryForObject(
                "select revoked_at is not null from refresh_tokens where token_hash = ?",
                Boolean.class,
                refreshTokenHasher.hash(expiredRawToken)
        )).isTrue();
    }
}
