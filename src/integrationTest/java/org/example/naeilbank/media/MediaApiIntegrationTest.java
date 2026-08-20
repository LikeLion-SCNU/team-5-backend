package org.example.naeilbank.media;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.naeilbank.global.jwt.JwtTokenProvider;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.UUID;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.head;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Testcontainers
@ActiveProfiles("integration-test")
@AutoConfigureMockMvc
@SpringBootTest
class MediaApiIntegrationTest {
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
    void privateUploadDedupDownloadHeadConditionalAndPhysicalDeleteHold() throws Exception {
        UUID ownerId = createUser();
        UUID otherId = createUser();
        grant(ownerId, "MEAL_AI");
        byte[] image = MediaIntegrationFixtures.png(32, 16);

        JsonNode created = objectMapper.readTree(upload(ownerId, "MEAL_INPUT", image, "image/png")
                .andExpect(status().isCreated())
                .andExpect(header().string(HttpHeaders.LOCATION, containsString("/api/v1/media/")))
                .andExpect(jsonPath("$.deduplicated").value(false))
                .andReturn().getResponse().getContentAsString());
        UUID mediaId = UUID.fromString(created.at("/media/id").asText());
        String etag = created.at("/media/etag").asText();

        upload(ownerId, "MEAL_INPUT", image, "image/png")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.media.id").value(mediaId.toString()))
                .andExpect(jsonPath("$.deduplicated").value(true));
        org.assertj.core.api.Assertions.assertThat(jdbcTemplate.queryForObject(
                "select count(*) from media_blobs where user_id = ? and purpose = 'meal_input'",
                Integer.class,
                ownerId
        )).isEqualTo(1);

        download(ownerId, mediaId)
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CONTENT_TYPE, "image/png"))
                .andExpect(header().longValue(HttpHeaders.CONTENT_LENGTH, image.length))
                .andExpect(header().string(HttpHeaders.ETAG, etag))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, containsString("no-store")))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(content().bytes(image));

        mockMvc.perform(get("/api/v1/media/{id}", mediaId)
                        .header(HttpHeaders.AUTHORIZATION, accessToken(ownerId))
                        .header(HttpHeaders.IF_NONE_MATCH, etag))
                .andExpect(status().isNotModified())
                .andExpect(header().string(HttpHeaders.ETAG, etag))
                .andExpect(header().doesNotExist(HttpHeaders.CONTENT_LENGTH));
        mockMvc.perform(head("/api/v1/media/{id}", mediaId)
                        .header(HttpHeaders.AUTHORIZATION, accessToken(ownerId)))
                .andExpect(status().isOk())
                .andExpect(header().longValue(HttpHeaders.CONTENT_LENGTH, image.length))
                .andExpect(header().string(HttpHeaders.ETAG, etag));

        mockMvc.perform(get("/api/v1/media/{id}", mediaId)
                        .header(HttpHeaders.AUTHORIZATION, accessToken(otherId)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("MEDIA_NOT_FOUND"));
        mockMvc.perform(delete("/api/v1/media/{id}", mediaId)
                        .header(HttpHeaders.AUTHORIZATION, accessToken(otherId)))
                .andExpect(status().isNotFound());
        mockMvc.perform(delete("/api/v1/media/{id}", mediaId)
                        .header(HttpHeaders.AUTHORIZATION, accessToken(ownerId)))
                .andExpect(status().isNoContent());
        org.assertj.core.api.Assertions.assertThat(jdbcTemplate.queryForObject(
                "select count(*) from media_blobs where id = ?", Integer.class, mediaId
        )).isZero();
        mockMvc.perform(get("/api/v1/media/{id}", mediaId)
                        .header(HttpHeaders.AUTHORIZATION, accessToken(ownerId)))
                .andExpect(status().isNotFound());
    }

    @Test
    void referencedMealInputDeleteReturnsConflictAndPreservesContent() throws Exception {
        UUID ownerId = createUser();
        grant(ownerId, "MEAL_AI");
        byte[] image = MediaIntegrationFixtures.png(8, 8);
        JsonNode created = objectMapper.readTree(upload(ownerId, "MEAL_INPUT", image, "image/png")
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString());
        UUID mediaId = UUID.fromString(created.at("/media/id").asText());
        jdbcTemplate.update("""
                        insert into meal_records (id, user_id, record_date, status, media_blob_id)
                        values (?, ?, current_date, 'analyzing', ?)
                        """,
                UUID.randomUUID(),
                ownerId,
                mediaId
        );

        mockMvc.perform(delete("/api/v1/media/{id}", mediaId)
                        .header(HttpHeaders.AUTHORIZATION, accessToken(ownerId)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("MEDIA_IN_USE"));

        org.assertj.core.api.Assertions.assertThat(jdbcTemplate.queryForObject(
                "select octet_length(content) from media_blobs where id = ?",
                Integer.class,
                mediaId
        )).isEqualTo(image.length);
        org.assertj.core.api.Assertions.assertThat(jdbcTemplate.queryForObject(
                "select count(*) from meal_records where media_blob_id = ?",
                Integer.class,
                mediaId
        )).isEqualTo(1);
    }

    private ResultActions upload(UUID userId, String purpose, byte[] content, String contentType)
            throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", "ignored.png", contentType, content);
        return mockMvc.perform(multipart("/api/v1/media/{purpose}", purpose)
                .file(file)
                .header(HttpHeaders.AUTHORIZATION, accessToken(userId)));
    }

    private ResultActions download(UUID userId, UUID mediaId) throws Exception {
        MvcResult pending = mockMvc.perform(get("/api/v1/media/{id}", mediaId)
                        .header(HttpHeaders.AUTHORIZATION, accessToken(userId)))
                .andExpect(request().asyncStarted())
                .andReturn();
        return mockMvc.perform(asyncDispatch(pending));
    }

    private UUID createUser() {
        UUID userId = UUID.randomUUID();
        jdbcTemplate.update(
                "insert into users (id, email, auth_provider, role) values (?, ?, 'email', 'USER')",
                userId,
                "media-" + userId + "@example.com"
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
