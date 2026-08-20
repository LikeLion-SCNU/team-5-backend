package org.example.naeilbank.evidence;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.naeilbank.global.jwt.JwtTokenProvider;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

abstract class EvidenceIntegrationSupport {
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("evidence_api_test")
            .withUsername("naeil")
            .withPassword("naeil_test");

    static {
        POSTGRES.start();
    }

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

    @BeforeEach
    void cleanEvidenceData() {
        jdbcTemplate.execute("""
                truncate table
                    ledger_entries,
                    conversion_rules,
                    sources,
                    audit_events,
                    users
                restart identity cascade
                """);
    }

    UUID createUser(String role) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update(
                "insert into users (id, email, auth_provider, role) values (?, ?, 'email', ?)",
                id, "evidence-" + id + "@example.com", role
        );
        return id;
    }

    String token(UUID userId, String role) {
        return "Bearer " + jwtTokenProvider.createToken(userId, "evidence@example.com", role);
    }

    JsonNode createSource(UUID adminId, String title, String url) throws Exception {
        String response = mockMvc.perform(post("/api/admin/sources")
                        .header(HttpHeaders.AUTHORIZATION, token(adminId, "ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(sourcePayload(title, url, true)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(response);
    }

    JsonNode createRule(UUID adminId, UUID sourceId, String label, int minutes) throws Exception {
        String response = mockMvc.perform(post("/api/admin/rules")
                        .header(HttpHeaders.AUTHORIZATION, token(adminId, "ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(rulePayload(sourceId, label, minutes, true, Map.of("minimum", 1))))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(response);
    }

    String sourcePayload(String title, String url, boolean active) throws Exception {
        Map<String, Object> content = new LinkedHashMap<>();
        content.put("title", title);
        content.put("authors", "검증 저자");
        content.put("journal", "검증 저널");
        content.put("publicationYear", 2025);
        content.put("doiUrl", url);
        content.put("summaryKo", "한국어 요약");
        content.put("scopeKo", "적용 범위");
        content.put("limitationsKo", "한계 설명");
        return objectMapper.writeValueAsString(Map.of("content", content, "active", active));
    }

    String versionSourcePayload(String title, String url, boolean active, long expected) throws Exception {
        JsonNode created = objectMapper.readTree(sourcePayload(title, url, active));
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("content", objectMapper.convertValue(created.get("content"), Map.class));
        payload.put("active", active);
        payload.put("expectedVersion", expected);
        return objectMapper.writeValueAsString(payload);
    }

    String rulePayload(
            UUID sourceId,
            String label,
            int minutes,
            boolean active,
            Object condition
    ) throws Exception {
        Map<String, Object> content = new LinkedHashMap<>();
        content.put("habitType", "activity");
        content.put("label", label);
        content.put("condition", condition);
        content.put("minutesDelta", minutes);
        content.put("unit", "per_day");
        content.put("sourceId", sourceId);
        return objectMapper.writeValueAsString(Map.of("content", content, "active", active));
    }

    String versionRulePayload(
            UUID sourceId,
            String label,
            int minutes,
            boolean active,
            long expected
    ) throws Exception {
        JsonNode created = objectMapper.readTree(rulePayload(
                sourceId, label, minutes, active, Map.of("minimum", 2))
        );
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("content", objectMapper.convertValue(created.get("content"), Map.class));
        payload.put("active", active);
        payload.put("expectedVersion", expected);
        return objectMapper.writeValueAsString(payload);
    }

    String activation(boolean active, long expectedVersion) throws Exception {
        return objectMapper.writeValueAsString(Map.of(
                "active", active,
                "expectedVersion", expectedVersion
        ));
    }
}
