package org.example.naeilbank.auth;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;

import java.io.IOException;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

abstract class KakaoAuthIntegrationSupport {
    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("naeil_bank_kakao_test")
            .withUsername("naeil")
            .withPassword("naeil_test");

    static final MockWebServer KAKAO = startKakaoServer();

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("kakao.token-uri", () -> KAKAO.url("/oauth/token").toString());
        registry.add("kakao.user-info-uri", () -> KAKAO.url("/v2/user/me").toString());
    }

    @AfterAll
    static void stopKakaoServer() throws IOException {
        KAKAO.shutdown();
    }

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ObjectMapper objectMapper;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @BeforeEach
    void cleanAuthTables() {
        jdbcTemplate.update("delete from users");
    }

    JsonNode kakaoLogin(String userInfoBody) throws Exception {
        enqueueKakaoLogin(userInfoBody);
        return kakaoLoginWithCode("valid-code");
    }

    JsonNode kakaoLoginWithCode(String code) throws Exception {
        return objectMapper.readTree(mockMvc.perform(post("/api/v1/auth/kakao")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("code", code))))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString());
    }

    void assertKakaoFallbackEmail(long kakaoId, String userInfoBody) throws Exception {
        JsonNode response = kakaoLogin(userInfoBody);
        String expectedEmail = "kakao_" + kakaoId + "@users.invalid";

        assertThat(response.at("/user/email").asText()).isEqualTo(expectedEmail);
        Map<String, Object> kakaoUser = jdbcTemplate.queryForMap(
                "select auth_provider, kakao_id from users where email = ?",
                expectedEmail
        );
        assertThat(kakaoUser.get("auth_provider")).isEqualTo("kakao");
        assertThat(kakaoUser.get("kakao_id")).isEqualTo(String.valueOf(kakaoId));
    }

    void assertInvalidKakaoPayload(String userInfoBody) throws Exception {
        enqueueKakaoLogin(userInfoBody);
        mockMvc.perform(post("/api/v1/auth/kakao")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("code", "invalid-payload-code"))))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("KAKAO_AUTH_FAILED"));
    }

    void enqueueKakaoLogin(String userInfoBody) {
        KAKAO.enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("{\"access_token\":\"kakao-access-token\"}"));
        KAKAO.enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody(userInfoBody));
    }

    Dispatcher concurrentLoginDispatcher() {
        return new Dispatcher() {
            @Override
            public MockResponse dispatch(RecordedRequest request) {
                if (request.getPath() != null && request.getPath().startsWith("/oauth/token")) {
                    return new MockResponse()
                            .setResponseCode(200)
                            .setHeader("Content-Type", "application/json")
                            .setBody("{\"access_token\":\"kakao-access-token\"}");
                }
                return new MockResponse()
                        .setResponseCode(200)
                        .setHeader("Content-Type", "application/json")
                        .setBody("""
                                {
                                  "id": 4401,
                                  "properties": {"nickname": "concurrent"},
                                  "kakao_account": {
                                    "email": "concurrent@example.com",
                                    "is_email_valid": true,
                                    "is_email_verified": true
                                  }
                                }
                                """);
            }
        };
    }

    private static MockWebServer startKakaoServer() {
        try {
            MockWebServer server = new MockWebServer();
            server.start();
            return server;
        } catch (IOException e) {
            throw new IllegalStateException("Could not start Kakao mock server", e);
        }
    }
}
