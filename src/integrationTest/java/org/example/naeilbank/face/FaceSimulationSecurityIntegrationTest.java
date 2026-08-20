package org.example.naeilbank.face;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.naeilbank.domain.face.FaceSimulationImageGenerator;
import org.example.naeilbank.domain.face.FaceSimulationProcessor;
import org.example.naeilbank.domain.model.entity.FaceSimulationOutput;
import org.example.naeilbank.global.jwt.JwtTokenProvider;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.example.naeilbank.face.FaceIntegrationFixtures.png;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Testcontainers
@ActiveProfiles("integration-test")
@AutoConfigureMockMvc
@SpringBootTest(properties = "app.face-simulation.worker-enabled=false")
class FaceSimulationSecurityIntegrationTest {
    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("naeil_bank_face_security")
            .withUsername("naeil")
            .withPassword("naeil_test");

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired JwtTokenProvider jwtTokenProvider;
    @Autowired FaceSimulationProcessor processor;
    @MockBean FaceSimulationImageGenerator imageGenerator;

    @Test
    void anonymousFaceSimulationAndMediaReadsAreRejected() throws Exception {
        UUID unknown = UUID.randomUUID();
        mockMvc.perform(get("/api/v1/face-simulations/{id}", unknown))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/face-media/{id}", unknown))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void revokedConsentBlocksCreateAndActiveQuotaIsBounded() throws Exception {
        UUID userId = createUser("face-consent");
        grantFace(userId);
        UUID sourceId = uploadFaceInput(userId, png(12, 12));
        jdbcTemplate.update("""
                update consents set granted = false, revoked_at = now(), version = version + 1
                where user_id = ? and purpose = 'FACE_AI'
                """, userId);

        mockMvc.perform(post("/api/v1/face-simulations")
                        .header(HttpHeaders.AUTHORIZATION, accessToken(userId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request(sourceId, "revoked")))
                .andExpect(status().isForbidden());
        assertThat(simulationCount(userId)).isZero();

        jdbcTemplate.update("""
                update consents set granted = true, revoked_at = null, version = version + 1
                where user_id = ? and purpose = 'FACE_AI'
                """, userId);
        for (int index = 0; index < 3; index++) {
            mockMvc.perform(post("/api/v1/face-simulations")
                            .header(HttpHeaders.AUTHORIZATION, accessToken(userId))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(request(sourceId, "quota-" + index)))
                    .andExpect(status().isAccepted());
        }
        mockMvc.perform(post("/api/v1/face-simulations")
                        .header(HttpHeaders.AUTHORIZATION, accessToken(userId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request(sourceId, "quota-overflow")))
                .andExpect(status().isTooManyRequests());
        assertThat(simulationCount(userId)).isEqualTo(3);
    }

    private UUID uploadFaceInput(UUID userId, byte[] content) throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", "face.png", "image/png", content);
        JsonNode response = objectMapper.readTree(mockMvc.perform(multipart("/api/v1/media/FACE_INPUT")
                        .file(file)
                        .header(HttpHeaders.AUTHORIZATION, accessToken(userId)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString());
        return UUID.fromString(response.at("/media/id").asText());
    }

    private UUID createUser(String prefix) {
        UUID userId = UUID.randomUUID();
        jdbcTemplate.update(
                "insert into users (id, email, auth_provider, role) values (?, ?, 'email', 'USER')",
                userId,
                prefix + "-" + userId + "@example.com"
        );
        return userId;
    }

    private void grantFace(UUID userId) {
        jdbcTemplate.update("""
                insert into consents
                    (user_id, purpose, granted, granted_at, consent_version, text_hash, version)
                values (?, 'FACE_AI', true, ?, 1, ?, 0)
                """, userId, Instant.parse("2026-08-20T00:00:00Z"), "f".repeat(64));
    }

    private int simulationCount(UUID userId) {
        return jdbcTemplate.queryForObject(
                "select count(*) from face_simulations where user_id = ?", Integer.class, userId);
    }

    private String request(UUID sourceId, String key) {
        return """
                {"sourceMediaId":"%s","idempotencyKey":"%s","trendDescription":"wellness",
                "selfPhotoConfirmed":true,"adultConfirmed":true,"disclaimerAccepted":true}
                """.formatted(sourceId, key);
    }

    private String accessToken(UUID userId) {
        return "Bearer " + jwtTokenProvider.createToken(userId, "face@example.com", "USER");
    }

}
