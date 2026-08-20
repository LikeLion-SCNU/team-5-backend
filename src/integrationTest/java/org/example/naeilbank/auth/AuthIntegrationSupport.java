package org.example.naeilbank.auth;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.naeilbank.domain.auth.service.RefreshTokenHasher;
import org.example.naeilbank.global.jwt.JwtTokenProvider;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.ResultMatcher;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

abstract class AuthIntegrationSupport {
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
    RefreshTokenHasher refreshTokenHasher;

    @Autowired
    JwtTokenProvider jwtTokenProvider;

    @Autowired
    PasswordEncoder passwordEncoder;

    @BeforeEach
    void cleanAuthTables() {
        jdbcTemplate.update("delete from users");
    }

    JsonNode login(String email, String password) throws Exception {
        return objectMapper.readTree(mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "email", email,
                                "password", password
                        ))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
    }

    ResultActions refresh(String refreshToken, ResultMatcher expectedStatus) throws Exception {
        return mockMvc.perform(post("/api/v1/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("refreshToken", refreshToken))))
                .andExpect(expectedStatus);
    }

    void assertAuthSchemaMetadataMatchesRepositoryContract() {
        assertThat(jdbcTemplate.queryForObject("""
                        select data_type
                        from information_schema.columns
                        where table_schema = 'public'
                          and table_name = 'users'
                          and column_name = 'id'
                        """, String.class))
                .isEqualTo("uuid");
        assertThat(jdbcTemplate.queryForObject("""
                        select count(*)
                        from information_schema.columns
                        where table_schema = 'public'
                          and table_name = 'users'
                          and column_name in ('password_hash', 'auth_provider', 'role', 'kakao_id')
                        """, Integer.class))
                .isEqualTo(4);
        assertThat(jdbcTemplate.queryForObject("""
                        select count(*)
                        from information_schema.columns
                        where table_schema = 'public'
                          and table_name = 'refresh_tokens'
                          and column_name in ('family_id', 'previous_token_hash', 'used_at', 'reuse_detected_at')
                        """, Integer.class))
                .isEqualTo(4);
        assertThat(jdbcTemplate.queryForObject("""
                        select count(*)
                        from pg_constraint
                        where conrelid = 'public.refresh_tokens'::regclass
                          and conname = 'refresh_tokens_token_hash_key'
                        """, Integer.class))
                .isEqualTo(1);
    }
}
