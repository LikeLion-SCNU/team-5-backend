package org.example.naeilbank.consent;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.naeilbank.global.jwt.JwtTokenProvider;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Testcontainers
@ActiveProfiles("integration-test")
@AutoConfigureMockMvc
@SpringBootTest
class ConsentApiIntegrationTest {
    private static final String TEXT_HASH = "a".repeat(64);

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("naeil_bank_test")
            .withUsername("naeil")
            .withPassword("naeil_test");

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired
    MockMvc mockMvc;
    @Autowired
    ObjectMapper objectMapper;
    @Autowired
    JdbcTemplate jdbcTemplate;
    @Autowired
    JwtTokenProvider jwtTokenProvider;

    @Test
    void authenticationValidationLifecycleReplayAndAuditContractsHold() throws Exception {
        mockMvc.perform(get("/api/v1/consents"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"));

        UUID userId = createUser();
        String token = accessToken(userId);
        mockMvc.perform(get("/api/v1/consents").header("Authorization", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.consents.length()").value(4))
                .andExpect(jsonPath("$.consents[0].purpose").value("HEALTH_COLLECTION"))
                .andExpect(jsonPath("$.consents[0].granted").value(false))
                .andExpect(jsonPath("$.consents[3].purpose").value("NOTIFICATION"));

        String grantKey = "grant-health-" + UUID.randomUUID();
        String grant = changeBody(true, 2, 0, grantKey, TEXT_HASH);
        change(userId, "HEALTH_COLLECTION", grant)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.granted").value(true))
                .andExpect(jsonPath("$.resourceVersion").value(1))
                .andExpect(jsonPath("$.replayed").value(false));
        change(userId, "HEALTH_COLLECTION", grant)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.replayed").value(true));

        change(userId, "HEALTH_COLLECTION", changeBody(false, 2, 0, grantKey, TEXT_HASH))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_CONFLICT"));
        change(userId, "HEALTH_COLLECTION", changeBody(false, 2, 9, "stale-key", TEXT_HASH))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONSENT_VERSION_CONFLICT"));
        change(userId, "HEALTH_COLLECTION", changeBody(false, 1, 1, "lower-key", TEXT_HASH))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONSENT_VERSION_CONFLICT"));

        change(userId, "HEALTH_COLLECTION", changeBody(false, 2, 1, "withdraw-key", TEXT_HASH))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.granted").value(false))
                .andExpect(jsonPath("$.resourceVersion").value(2));

        mockMvc.perform(put("/api/v1/consents/MEAL_AI")
                        .header("Authorization", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(changeBody(true, 0, 0, "bad-version", TEXT_HASH)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        mockMvc.perform(put("/api/v1/consents/UNKNOWN_PURPOSE")
                        .header("Authorization", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(changeBody(true, 1, 0, "invalid-purpose", TEXT_HASH)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_CONSENT_PURPOSE"));
        mockMvc.perform(put("/api/v1/consents/MEAL_AI")
                        .header("Authorization", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(changeBody(true, 1, 0, "bad-hash", "not-a-hash")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from audit_events where user_id = ? and event_type = 'CONSENT_CHANGED'",
                Integer.class,
                userId
        )).isEqualTo(2);
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from audit_events where user_id = ? and detail_json::text like ?",
                Integer.class,
                userId,
                "%" + grantKey + "%"
        )).isZero();
    }

    @Test
    void fourPurposesRemainIndependentAndTenantScoped() throws Exception {
        UUID firstUser = createUser();
        UUID secondUser = createUser();
        String sharedKey = "tenant-key-" + UUID.randomUUID();
        String[] purposes = {"HEALTH_COLLECTION", "MEAL_AI", "FACE_AI", "NOTIFICATION"};

        for (int index = 0; index < purposes.length; index++) {
            String key = index == 1 ? sharedKey : "purpose-" + index + "-" + UUID.randomUUID();
            change(firstUser, purposes[index], changeBody(true, 1, 0, key, TEXT_HASH))
                    .andExpect(status().isOk());
        }
        mockMvc.perform(get("/api/v1/consents").header("Authorization", accessToken(firstUser)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.consents[0].granted").value(true))
                .andExpect(jsonPath("$.consents[1].granted").value(true))
                .andExpect(jsonPath("$.consents[2].granted").value(true))
                .andExpect(jsonPath("$.consents[3].granted").value(true));

        mockMvc.perform(get("/api/v1/consents").header("Authorization", accessToken(secondUser)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.consents[0].granted").value(false))
                .andExpect(jsonPath("$.consents[1].granted").value(false))
                .andExpect(jsonPath("$.consents[2].granted").value(false))
                .andExpect(jsonPath("$.consents[3].granted").value(false));
        change(secondUser, "MEAL_AI", changeBody(true, 1, 0, sharedKey, TEXT_HASH))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.replayed").value(false));

        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from consents where user_id = ? and granted",
                Integer.class,
                firstUser
        )).isEqualTo(4);
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from consents where user_id = ? and granted",
                Integer.class,
                secondUser
        )).isEqualTo(1);
    }

    private org.springframework.test.web.servlet.ResultActions change(
            UUID userId,
            String purpose,
            String body
    ) throws Exception {
        return mockMvc.perform(put("/api/v1/consents/{purpose}", purpose)
                .header("Authorization", accessToken(userId))
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    private String changeBody(
            boolean granted,
            int consentVersion,
            long expectedVersion,
            String idempotencyKey,
            String textHash
    ) throws Exception {
        return objectMapper.writeValueAsString(Map.of(
                "granted", granted,
                "consentVersion", consentVersion,
                "textHash", textHash,
                "expectedVersion", expectedVersion,
                "idempotencyKey", idempotencyKey
        ));
    }

    private UUID createUser() {
        UUID userId = UUID.randomUUID();
        jdbcTemplate.update(
                "insert into users (id, email, auth_provider, role) values (?, ?, 'email', 'USER')",
                userId,
                "consent-" + userId + "@example.com"
        );
        return userId;
    }

    private String accessToken(UUID userId) {
        return "Bearer " + jwtTokenProvider.createToken(userId, "consent@example.com", "USER");
    }
}
