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
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.example.naeilbank.face.FaceIntegrationFixtures.png;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.head;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Testcontainers
@ActiveProfiles("integration-test")
@AutoConfigureMockMvc
@SpringBootTest(properties = "app.face-simulation.worker-enabled=false")
class FaceSimulationApiIntegrationTest {
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
    @Autowired
    FaceSimulationProcessor faceSimulationProcessor;
    @MockBean
    FaceSimulationImageGenerator imageGenerator;

    @Test
    void createProcessOwnerOnlyMediaAndDeleteInputCleanup() throws Exception {
        UUID ownerId = createUser("face-owner");
        UUID otherId = createUser("face-other");
        grantFace(ownerId);
        grantFace(otherId);
        byte[] input = png(16, 16);
        byte[] current = png(8, 8);
        byte[] improved = png(10, 10);
        when(imageGenerator.generate(any(), any(), any())).thenAnswer(invocation -> {
            FaceSimulationOutput.Label label = invocation.getArgument(2);
            byte[] content = label == FaceSimulationOutput.Label.current ? current : improved;
            return new FaceSimulationImageGenerator.FaceGenerationResult(
                    "face-model-test",
                    "prompt-test",
                    List.of(new FaceSimulationImageGenerator.GeneratedImage(label, "image/png", content))
            );
        });

        UUID sourceMediaId = uploadFaceInput(ownerId, input);
        String request = """
                {
                  "sourceMediaId": "%s",
                  "idempotencyKey": "face-key-1",
                  "trendDescription": "balanced sleep and hydration",
                  "selfPhotoConfirmed": true,
                  "adultConfirmed": true,
                  "disclaimerAccepted": true
                }
                """.formatted(sourceMediaId);
        JsonNode created = objectMapper.readTree(mockMvc.perform(post("/api/v1/face-simulations")
                        .header(HttpHeaders.AUTHORIZATION, accessToken(ownerId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("queued"))
                .andExpect(jsonPath("$.outputs.length()").value(0))
                .andReturn().getResponse().getContentAsString());
        UUID simulationId = UUID.fromString(created.at("/id").asText());

        mockMvc.perform(post("/api/v1/face-simulations")
                        .header(HttpHeaders.AUTHORIZATION, accessToken(ownerId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(simulationId.toString()))
                .andExpect(jsonPath("$.replayed").value(true));

        assertThat(faceSimulationProcessor.processOneDue()).isTrue();
        JsonNode done = objectMapper.readTree(mockMvc.perform(get("/api/v1/face-simulations/{id}", simulationId)
                        .header(HttpHeaders.AUTHORIZATION, accessToken(ownerId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("done"))
                .andExpect(jsonPath("$.disclaimer").isNotEmpty())
                .andExpect(jsonPath("$.outputs.length()").value(2))
                .andExpect(jsonPath("$.outputs[0].label").value("current"))
                .andExpect(jsonPath("$.outputs[1].label").value("improved"))
                .andReturn().getResponse().getContentAsString());
        UUID currentMediaId = UUID.fromString(done.at("/outputs/0/mediaId").asText());
        UUID improvedMediaId = UUID.fromString(done.at("/outputs/1/mediaId").asText());
        mockMvc.perform(get("/api/v1/face-simulations")
                        .param("page", "0")
                        .param("size", "20")
                        .header(HttpHeaders.AUTHORIZATION, accessToken(ownerId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.hasNext").value(false));

        JsonNode second = objectMapper.readTree(mockMvc.perform(post("/api/v1/face-simulations")
                        .header(HttpHeaders.AUTHORIZATION, accessToken(ownerId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request.replace("face-key-1", "face-key-2")))
                .andExpect(status().isAccepted())
                .andReturn().getResponse().getContentAsString());
        UUID secondSimulationId = UUID.fromString(second.at("/id").asText());
        jdbcTemplate.update("""
                update face_simulations
                set status = 'processing',
                    processing_started_at = now() - interval '30 minutes',
                    claim_token = gen_random_uuid(),
                    attempt_count = 1
                where id = ?
                """, secondSimulationId);
        assertThat(faceSimulationProcessor.processOneDue()).isTrue();
        assertThat(jdbcTemplate.queryForObject(
                "select status from face_simulations where id = ?", String.class, secondSimulationId))
                .isEqualTo("done");
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from face_simulation_outputs where simulation_id in (?, ?)",
                Integer.class, simulationId, secondSimulationId)).isEqualTo(4);

        download(ownerId, sourceMediaId).andExpect(status().isOk()).andExpect(content().bytes(input));
        download(ownerId, currentMediaId).andExpect(status().isOk()).andExpect(content().bytes(current));
        mockMvc.perform(head("/api/v1/face-media/{id}", improvedMediaId)
                        .header(HttpHeaders.AUTHORIZATION, accessToken(ownerId)))
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header()
                        .exists(HttpHeaders.ETAG))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header()
                        .longValue(HttpHeaders.CONTENT_LENGTH, improved.length));
        mockMvc.perform(get("/api/v1/face-media/{id}", currentMediaId)
                        .header(HttpHeaders.AUTHORIZATION, accessToken(otherId)))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/v1/face-simulations/{id}", simulationId)
                        .header(HttpHeaders.AUTHORIZATION, accessToken(otherId)))
                .andExpect(status().isNotFound());
        mockMvc.perform(post("/api/v1/face-simulations/{id}/cancel", simulationId)
                        .header(HttpHeaders.AUTHORIZATION, accessToken(otherId)))
                .andExpect(status().isNotFound());
        mockMvc.perform(delete("/api/v1/face-simulations/{id}", simulationId)
                        .header(HttpHeaders.AUTHORIZATION, accessToken(otherId)))
                .andExpect(status().isNoContent());
        mockMvc.perform(delete("/api/v1/face-media/{id}", improvedMediaId)
                        .header(HttpHeaders.AUTHORIZATION, accessToken(otherId)))
                .andExpect(status().isNoContent());

        mockMvc.perform(delete("/api/v1/face-simulations/{id}", simulationId)
                        .header(HttpHeaders.AUTHORIZATION, accessToken(ownerId)))
                .andExpect(status().isNoContent());
        mockMvc.perform(delete("/api/v1/face-simulations/{id}", simulationId)
                        .header(HttpHeaders.AUTHORIZATION, accessToken(ownerId)))
                .andExpect(status().isNoContent());
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from media_blobs where id = ?", Integer.class, sourceMediaId)).isOne();
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from face_simulations where id = ?", Integer.class, secondSimulationId)).isOne();

        mockMvc.perform(delete("/api/v1/face-media/{id}", sourceMediaId)
                        .header(HttpHeaders.AUTHORIZATION, accessToken(ownerId)))
                .andExpect(status().isNoContent());
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from media_blobs where user_id = ?", Integer.class, ownerId)).isZero();
        mockMvc.perform(get("/api/v1/face-media/{id}", sourceMediaId)
                        .header(HttpHeaders.AUTHORIZATION, accessToken(ownerId)))
                .andExpect(status().isNotFound());
        mockMvc.perform(delete("/api/v1/face-media/{id}", sourceMediaId)
                        .header(HttpHeaders.AUTHORIZATION, accessToken(ownerId)))
                .andExpect(status().isNoContent());
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from audit_events where user_id = ? and event_type = 'FACE_DATA_DELETED'",
                Integer.class,
                ownerId
        )).isGreaterThanOrEqualTo(2);
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

    private org.springframework.test.web.servlet.ResultActions download(UUID userId, UUID mediaId) throws Exception {
        MvcResult pending = mockMvc.perform(get("/api/v1/face-media/{id}", mediaId)
                        .header(HttpHeaders.AUTHORIZATION, accessToken(userId)))
                .andExpect(request().asyncStarted())
                .andReturn();
        return mockMvc.perform(asyncDispatch(pending));
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
                        """,
                userId,
                OffsetDateTime.parse("2026-08-20T00:00:00Z"),
                "f".repeat(64)
        );
    }

    private String accessToken(UUID userId) {
        return "Bearer " + jwtTokenProvider.createToken(userId, "face@example.com", "USER");
    }

}
