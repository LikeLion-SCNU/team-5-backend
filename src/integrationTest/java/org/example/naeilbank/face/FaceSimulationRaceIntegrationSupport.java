package org.example.naeilbank.face;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.naeilbank.domain.face.FaceSimulationImageGenerator;
import org.example.naeilbank.domain.face.FaceSimulationProcessor;
import org.example.naeilbank.domain.model.entity.FaceSimulationOutput;
import org.example.naeilbank.global.jwt.JwtTokenProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.example.naeilbank.face.FaceIntegrationFixtures.png;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

abstract class FaceSimulationRaceIntegrationSupport {
    static final PostgreSQLContainer<?> POSTGRES;

    static {
        POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
                .withDatabaseName("naeil_bank_face_race")
                .withUsername("naeil")
                .withPassword("naeil_test");
        POSTGRES.start();
    }

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

    UUID createUser(String prefix) {
        UUID userId = UUID.randomUUID();
        jdbcTemplate.update(
                "insert into users (id, email, auth_provider, role) values (?, ?, 'email', 'USER')",
                userId,
                prefix + "-" + userId + "@example.com"
        );
        return userId;
    }

    void grantFace(UUID userId) {
        jdbcTemplate.update("""
                insert into consents
                    (user_id, purpose, granted, granted_at, consent_version, text_hash, version)
                values (?, 'FACE_AI', true, ?, 1, ?, 0)
                """, userId, Instant.parse("2026-08-20T00:00:00Z"), "f".repeat(64));
    }

    UUID uploadFaceInput(UUID userId) throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", "face.png", "image/png", png(12, 12));
        JsonNode response = objectMapper.readTree(mockMvc.perform(multipart("/api/v1/media/FACE_INPUT")
                        .file(file)
                        .header(HttpHeaders.AUTHORIZATION, accessToken(userId)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString());
        return UUID.fromString(response.at("/media/id").asText());
    }

    UUID createSimulation(UUID userId, UUID sourceId, String key) throws Exception {
        JsonNode response = objectMapper.readTree(mockMvc.perform(post("/api/v1/face-simulations")
                        .header(HttpHeaders.AUTHORIZATION, accessToken(userId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request(sourceId, key)))
                .andExpect(status().isAccepted())
                .andReturn().getResponse().getContentAsString());
        return UUID.fromString(response.at("/id").asText());
    }

    FaceSimulationImageGenerator.FaceGenerationResult generated(
            FaceSimulationOutput.Label label,
            byte[] output
    ) {
        return new FaceSimulationImageGenerator.FaceGenerationResult(
                "gpt-image-2",
                "prompt-test",
                List.of(new FaceSimulationImageGenerator.GeneratedImage(label, "image/png", output))
        );
    }

    int simulationCount(UUID userId) {
        return jdbcTemplate.queryForObject(
                "select count(*) from face_simulations where user_id = ?", Integer.class, userId);
    }

    String accessToken(UUID userId) {
        return "Bearer " + jwtTokenProvider.createToken(userId, "face@example.com", "USER");
    }

    private String request(UUID sourceId, String key) {
        return """
                {"sourceMediaId":"%s","idempotencyKey":"%s","trendDescription":"wellness",
                "selfPhotoConfirmed":true,"adultConfirmed":true,"disclaimerAccepted":true}
                """.formatted(sourceId, key);
    }
}
