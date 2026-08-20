package org.example.naeilbank.media;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.head;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Testcontainers
@ActiveProfiles("integration-test")
@AutoConfigureMockMvc
@SpringBootTest
class MediaFormatIntegrationTest {
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
    void jpegAndWebpTraverseAuthenticatedStorageAndCacheSurface() throws Exception {
        UUID userId = createUser();
        grantMealConsent(userId);
        List<FormatCase> formats = List.of(
                new FormatCase("image/jpeg", "fixture.jpg", MediaIntegrationFixtures.jpeg(24, 12)),
                new FormatCase("image/webp", "fixture.webp", MediaIntegrationFixtures.webp())
        );

        for (FormatCase format : formats) {
            JsonNode created = objectMapper.readTree(mockMvc.perform(multipart("/api/v1/media/MEAL_INPUT")
                            .file(new MockMultipartFile(
                                    "file", format.filename(), format.contentType(), format.bytes()))
                            .header(HttpHeaders.AUTHORIZATION, accessToken(userId)))
                    .andExpect(status().isCreated())
                    .andReturn().getResponse().getContentAsString());
            UUID mediaId = UUID.fromString(created.at("/media/id").asText());
            String etag = created.at("/media/etag").asText();
            assertThat(created.at("/media/contentType").asText()).isEqualTo(format.contentType());
            assertThat(created.at("/media/sizeBytes").asLong()).isEqualTo(format.bytes().length);
            assertThat(etag).matches("\"[0-9a-f]{64}\"");

            MvcResult pending = mockMvc.perform(get("/api/v1/media/{id}", mediaId)
                            .header(HttpHeaders.AUTHORIZATION, accessToken(userId)))
                    .andExpect(request().asyncStarted())
                    .andReturn();
            pending.getAsyncResult(5000);
            mockMvc.perform(asyncDispatch(pending))
                    .andExpect(status().isOk())
                    .andExpect(header().string(HttpHeaders.CONTENT_TYPE, format.contentType()))
                    .andExpect(header().longValue(HttpHeaders.CONTENT_LENGTH, format.bytes().length))
                    .andExpect(header().string(HttpHeaders.ETAG, etag))
                    .andExpect(content().bytes(format.bytes()));

            mockMvc.perform(head("/api/v1/media/{id}", mediaId)
                            .header(HttpHeaders.AUTHORIZATION, accessToken(userId)))
                    .andExpect(status().isOk())
                    .andExpect(header().string(HttpHeaders.CONTENT_TYPE, format.contentType()))
                    .andExpect(header().longValue(HttpHeaders.CONTENT_LENGTH, format.bytes().length))
                    .andExpect(header().string(HttpHeaders.ETAG, etag));
            mockMvc.perform(get("/api/v1/media/{id}", mediaId)
                            .header(HttpHeaders.AUTHORIZATION, accessToken(userId))
                            .header(HttpHeaders.IF_NONE_MATCH, etag))
                    .andExpect(status().isNotModified())
                    .andExpect(header().string(HttpHeaders.ETAG, etag));

            mockMvc.perform(delete("/api/v1/media/{id}", mediaId)
                            .header(HttpHeaders.AUTHORIZATION, accessToken(userId)))
                    .andExpect(status().isNoContent());
            assertThat(jdbcTemplate.queryForObject(
                    "select count(*) from media_blobs where id = ?", Integer.class, mediaId
            )).isZero();
        }
    }

    private UUID createUser() {
        UUID userId = UUID.randomUUID();
        jdbcTemplate.update(
                "insert into users (id, email, auth_provider, role) values (?, ?, 'email', 'USER')",
                userId,
                "media-format-" + userId + "@example.com"
        );
        return userId;
    }

    private void grantMealConsent(UUID userId) {
        jdbcTemplate.update("""
                        insert into consents
                            (user_id, purpose, granted, granted_at, consent_version, text_hash, version)
                        values (?, 'MEAL_AI', true, now(), 1, ?, 0)
                        """,
                userId,
                "a".repeat(64)
        );
    }

    private String accessToken(UUID userId) {
        return "Bearer " + jwtTokenProvider.createToken(userId, "media@example.com", "USER");
    }

    private record FormatCase(String contentType, String filename, byte[] bytes) {
    }
}
