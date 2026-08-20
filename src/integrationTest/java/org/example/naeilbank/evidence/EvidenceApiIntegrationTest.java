package org.example.naeilbank.evidence;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Testcontainers
@ActiveProfiles("integration-test")
@AutoConfigureMockMvc
@SpringBootTest
class EvidenceApiIntegrationTest extends EvidenceIntegrationSupport {

    @Test
    void authenticatedReadsAndAuditedAdminMutationsEnforceRolesAndValidation() throws Exception {
        UUID userId = createUser("USER");
        UUID adminId = createUser("ADMIN");

        mockMvc.perform(get("/api/v1/sources"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/admin/sources")
                        .header(HttpHeaders.AUTHORIZATION, token(userId, "USER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(sourcePayload("거부", "https://doi.org/10.1000/denied", true)))
                .andExpect(status().isForbidden());

        mockMvc.perform(post("/api/admin/sources")
                        .header(HttpHeaders.AUTHORIZATION, token(adminId, "ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(sourcePayload("잘못된 URL", "http://example.com/source", true)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_EVIDENCE_URL"));
        assertThat(jdbcTemplate.queryForObject("select count(*) from sources", Integer.class)).isZero();

        JsonNode source = createSource(adminId, "활동 연구 V1", "https://doi.org/10.1000/activity-v1");
        UUID sourceId = UUID.fromString(source.get("id").asText());
        mockMvc.perform(get("/api/v1/sources/{id}", sourceId)
                        .header(HttpHeaders.AUTHORIZATION, token(userId, "USER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.summaryKo").value("한국어 요약"))
                .andExpect(jsonPath("$.scopeKo").value("적용 범위"))
                .andExpect(jsonPath("$.limitationsKo").value("한계 설명"));

        JsonNode rule = createRule(adminId, sourceId, "활동 규칙 V1", 10);
        UUID ruleId = UUID.fromString(rule.get("id").asText());
        mockMvc.perform(get("/api/v1/rules?habitType=activity")
                        .header(HttpHeaders.AUTHORIZATION, token(userId, "USER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rules.length()").value(1))
                .andExpect(jsonPath("$.rules[0].id").value(ruleId.toString()))
                .andExpect(jsonPath("$.rules[0].source.id").value(sourceId.toString()));

        mockMvc.perform(post("/api/admin/rules")
                        .header(HttpHeaders.AUTHORIZATION, token(adminId, "ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(rulePayload(sourceId, "0분 규칙", 0, true, Map.of())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_EVIDENCE_CONTENT"));
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from audit_events where event_type = 'EVIDENCE_MUTATED'",
                Integer.class
        )).isEqualTo(2);
    }

    @Test
    void newActiveVersionAffectsFutureReadsWhileLedgerKeepsHistoricalEvidence() throws Exception {
        UUID userId = createUser("USER");
        UUID otherId = createUser("USER");
        UUID adminId = createUser("ADMIN");
        JsonNode sourceV1 = createSource(adminId, "활동 연구 V1", "https://doi.org/10.1000/source-v1");
        UUID sourceV1Id = UUID.fromString(sourceV1.get("id").asText());
        JsonNode ruleV1 = createRule(adminId, sourceV1Id, "활동 규칙 V1", 10);
        UUID ruleV1Id = UUID.fromString(ruleV1.get("id").asText());

        Long ledgerId = jdbcTemplate.queryForObject("""
                insert into ledger_entries (user_id, entry_date, habit_type, minutes_delta, rule_id)
                values (?, current_date, 'activity', 10, ?) returning id
                """, Long.class, userId, ruleV1Id);
        int auditBeforeRejectedDeactivate = auditCount(adminId);
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put(
                        "/api/admin/sources/{id}/activation", sourceV1Id)
                        .header(HttpHeaders.AUTHORIZATION, token(adminId, "ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(activation(false, 1)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("EVIDENCE_SOURCE_IN_USE"));
        assertThat(auditCount(adminId)).isEqualTo(auditBeforeRejectedDeactivate);

        String sourceV2Body = versionSourcePayload(
                "활동 연구 V2", "https://doi.org/10.1000/source-v2", true, 1
        );
        JsonNode sourceV2 = objectMapper.readTree(mockMvc.perform(post(
                                "/api/admin/sources/{id}/versions", sourceV1Id)
                        .header(HttpHeaders.AUTHORIZATION, token(adminId, "ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON).content(sourceV2Body))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
        UUID sourceV2Id = UUID.fromString(sourceV2.get("id").asText());

        mockMvc.perform(get("/api/v1/sources")
                        .header(HttpHeaders.AUTHORIZATION, token(userId, "USER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sources.length()").value(1))
                .andExpect(jsonPath("$.sources[0].id").value(sourceV2Id.toString()));

        JsonNode ruleV2 = objectMapper.readTree(mockMvc.perform(post(
                                "/api/admin/rules/{id}/versions", ruleV1Id)
                        .header(HttpHeaders.AUTHORIZATION, token(adminId, "ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(versionRulePayload(sourceV2Id, "활동 규칙 V2", 20, true, 1)))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
        UUID ruleV2Id = UUID.fromString(ruleV2.get("id").asText());

        mockMvc.perform(get("/api/v1/rules")
                        .header(HttpHeaders.AUTHORIZATION, token(userId, "USER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rules.length()").value(1))
                .andExpect(jsonPath("$.rules[0].id").value(ruleV2Id.toString()))
                .andExpect(jsonPath("$.rules[0].minutesDelta").value(20));
        mockMvc.perform(get("/api/v1/ledger/{id}/evidence", ledgerId)
                        .header(HttpHeaders.AUTHORIZATION, token(userId, "USER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rule.id").value(ruleV1Id.toString()))
                .andExpect(jsonPath("$.rule.minutesDelta").value(10))
                .andExpect(jsonPath("$.rule.source.id").value(sourceV1Id.toString()))
                .andExpect(jsonPath("$.rule.source.title").value("활동 연구 V1"));
        mockMvc.perform(get("/api/v1/ledger/{id}/evidence", ledgerId)
                        .header(HttpHeaders.AUTHORIZATION, token(otherId, "USER")))
                .andExpect(status().isNotFound());

        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from ledger_entries where id = ? and rule_id = ?",
                Integer.class, ledgerId, ruleV1Id
        )).isOne();
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from conversion_rules where logical_key = ?",
                Integer.class, UUID.fromString(ruleV1.get("logicalKey").asText())
        )).isEqualTo(2);

        mockMvc.perform(put("/api/admin/rules/{id}/activation", ruleV1Id)
                        .header(HttpHeaders.AUTHORIZATION, token(adminId, "ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON).content(activation(true, 1)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("EVIDENCE_VERSION_CONFLICT"));
        mockMvc.perform(put("/api/admin/rules/{id}/activation", ruleV2Id)
                        .header(HttpHeaders.AUTHORIZATION, token(adminId, "ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON).content(activation(false, 1)))
                .andExpect(status().isOk());
        mockMvc.perform(put("/api/admin/sources/{id}/activation", sourceV2Id)
                        .header(HttpHeaders.AUTHORIZATION, token(adminId, "ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON).content(activation(false, 1)))
                .andExpect(status().isOk());

        int auditBeforeInactiveSource = auditCount(adminId);
        int rulesBeforeInactiveSource = jdbcTemplate.queryForObject(
                "select count(*) from conversion_rules", Integer.class
        );
        mockMvc.perform(post("/api/admin/rules")
                        .header(HttpHeaders.AUTHORIZATION, token(adminId, "ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(rulePayload(sourceV2Id, "비활성 출처 규칙", 30, true, Map.of())))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INACTIVE_EVIDENCE_SOURCE"));
        assertThat(auditCount(adminId)).isEqualTo(auditBeforeInactiveSource);
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from conversion_rules", Integer.class
        )).isEqualTo(rulesBeforeInactiveSource);
    }

    private int auditCount(UUID adminId) {
        return jdbcTemplate.queryForObject(
                "select count(*) from audit_events where user_id = ? and event_type = 'EVIDENCE_MUTATED'",
                Integer.class, adminId
        );
    }
}
