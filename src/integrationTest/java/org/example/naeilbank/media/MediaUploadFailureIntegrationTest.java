package org.example.naeilbank.media;

import org.example.naeilbank.global.jwt.JwtTokenProvider;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.Arrays;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Testcontainers
@ActiveProfiles("integration-test")
@AutoConfigureMockMvc
@SpringBootTest
class MediaUploadFailureIntegrationTest {
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
    JdbcTemplate jdbcTemplate;
    @Autowired
    JwtTokenProvider jwtTokenProvider;

    @Test
    void anonymousMissingConsentInvalidPurposeOversizeAndSpoofingAreRejected() throws Exception {
        byte[] png = MediaIntegrationFixtures.png(16, 16);
        MockMultipartFile anonymousFile = file(png, "image/png");
        mockMvc.perform(multipart("/api/v1/media/MEAL_INPUT").file(anonymousFile))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"));
        mockMvc.perform(get("/api/v1/media/{id}", UUID.randomUUID()))
                .andExpect(status().isUnauthorized());

        UUID userId = createUser();
        upload(userId, "MEAL_INPUT", png, "image/png")
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("CONSENT_REQUIRED"));
        grant(userId, "MEAL_AI");

        upload(userId, "NOT_PUBLIC", png, "image/png")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_MEDIA_PURPOSE"));
        upload(userId, "MEAL_INPUT", new byte[10 * 1024 * 1024 + 1], "image/png")
                .andExpect(status().isPayloadTooLarge())
                .andExpect(jsonPath("$.code").value("MEDIA_TOO_LARGE"));
        upload(userId, "MEAL_INPUT", png, "image/jpeg")
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.code").value("MEDIA_TYPE_MISMATCH"));
        upload(userId, "MEAL_INPUT", png, "text/plain")
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.code").value("MEDIA_TYPE_UNSUPPORTED"));
        upload(userId, "MEAL_INPUT", Arrays.copyOf(png, 20), "image/png")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_IMAGE"));

        org.assertj.core.api.Assertions.assertThat(jdbcTemplate.queryForObject(
                "select count(*) from media_blobs where user_id = ?", Integer.class, userId
        )).isZero();
    }

    private ResultActions upload(UUID userId, String purpose, byte[] content, String contentType)
            throws Exception {
        return mockMvc.perform(multipart("/api/v1/media/{purpose}", purpose)
                .file(file(content, contentType))
                .header(HttpHeaders.AUTHORIZATION, accessToken(userId)));
    }

    private MockMultipartFile file(byte[] content, String contentType) {
        return new MockMultipartFile("file", "untrusted-name.bin", contentType, content);
    }

    private UUID createUser() {
        UUID userId = UUID.randomUUID();
        jdbcTemplate.update(
                "insert into users (id, email, auth_provider, role) values (?, ?, 'email', 'USER')",
                userId,
                "media-failure-" + userId + "@example.com"
        );
        return userId;
    }

    private void grant(UUID userId, String purpose) {
        jdbcTemplate.update("""
                        insert into consents
                            (user_id, purpose, granted, granted_at, consent_version, text_hash, version)
                        values (?, ?, true, now(), 1, ?, 0)
                        """,
                userId,
                purpose,
                "a".repeat(64)
        );
    }

    private String accessToken(UUID userId) {
        return "Bearer " + jwtTokenProvider.createToken(userId, "media@example.com", "USER");
    }
}
