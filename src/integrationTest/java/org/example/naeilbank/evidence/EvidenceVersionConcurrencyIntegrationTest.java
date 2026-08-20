package org.example.naeilbank.evidence;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ActiveProfiles("integration-test")
@AutoConfigureMockMvc
@SpringBootTest
class EvidenceVersionConcurrencyIntegrationTest extends EvidenceIntegrationSupport {

    @Test
    void concurrentVersioningAllowsOneWinnerAndHistoricalMembersCannotFork() throws Exception {
        UUID adminId = createUser("ADMIN");
        JsonNode source = createSource(adminId, "동시성 출처 V1", "https://doi.org/10.1000/concurrent-source");
        UUID sourceId = UUID.fromString(source.get("id").asText());
        JsonNode rule = createRule(adminId, sourceId, "동시성 규칙 V1", 10);
        UUID ruleId = UUID.fromString(rule.get("id").asText());

        String sourceV2 = versionSourcePayload(
                "동시성 출처 V2", "https://doi.org/10.1000/concurrent-source-v2", true, 1
        );
        assertThat(runTogether(() -> postSourceVersion(adminId, sourceId, sourceV2)))
                .containsExactlyInAnyOrder(201, 409);
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from sources where logical_key = ?",
                Integer.class,
                UUID.fromString(source.get("logicalKey").asText())
        )).isEqualTo(2);

        String ruleV2 = versionRulePayload(sourceId, "동시성 규칙 V2", 20, true, 1);
        assertThat(runTogether(() -> postRuleVersion(adminId, ruleId, ruleV2)))
                .containsExactlyInAnyOrder(201, 409);
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from conversion_rules where logical_key = ?",
                Integer.class,
                UUID.fromString(rule.get("logicalKey").asText())
        )).isEqualTo(2);

        mockMvc.perform(post("/api/admin/sources/{id}/versions", sourceId)
                        .header(HttpHeaders.AUTHORIZATION, token(adminId, "ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(versionSourcePayload(
                                "금지된 분기", "https://doi.org/10.1000/forbidden-fork", true, 2
                        )))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("EVIDENCE_VERSION_CONFLICT"));
        mockMvc.perform(post("/api/admin/rules/{id}/versions", ruleId)
                        .header(HttpHeaders.AUTHORIZATION, token(adminId, "ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(versionRulePayload(sourceId, "금지된 규칙 분기", 30, true, 2)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("EVIDENCE_VERSION_CONFLICT"));
    }

    private int postSourceVersion(UUID adminId, UUID sourceId, String body) throws Exception {
        return mockMvc.perform(post("/api/admin/sources/{id}/versions", sourceId)
                        .header(HttpHeaders.AUTHORIZATION, token(adminId, "ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andReturn().getResponse().getStatus();
    }

    private int postRuleVersion(UUID adminId, UUID ruleId, String body) throws Exception {
        return mockMvc.perform(post("/api/admin/rules/{id}/versions", ruleId)
                        .header(HttpHeaders.AUTHORIZATION, token(adminId, "ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andReturn().getResponse().getStatus();
    }

    private List<Integer> runTogether(Callable<Integer> request) throws Exception {
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            Callable<Integer> synchronizedRequest = () -> {
                ready.countDown();
                if (!start.await(5, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("Concurrent evidence requests did not become ready");
                }
                return request.call();
            };
            Future<Integer> first = executor.submit(synchronizedRequest);
            Future<Integer> second = executor.submit(synchronizedRequest);
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            return List.of(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS));
        }
    }
}
