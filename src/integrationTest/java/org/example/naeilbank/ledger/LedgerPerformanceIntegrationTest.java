package org.example.naeilbank.ledger;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Testcontainers
@ActiveProfiles("integration-test")
@SpringBootTest
@AutoConfigureMockMvc
class LedgerPerformanceIntegrationTest extends LedgerIntegrationSupport {
    private static final Duration STATEMENT_LATENCY_BUDGET = Duration.ofSeconds(5);

    @Test
    void statementPageStaysWithinBudgetAtTenThousandRows() throws Exception {
        UUID user = user("perf", false);
        UUID other = user("perf-other", false);
        bulkEntries(user, other, rule(), 10_050);

        long startedAt = System.nanoTime();
        mvc.perform(get("/api/v1/ledger/statements")
                        .param("from", "2026-01-01").param("to", "2026-03-31")
                        .param("page", "0").param("size", "20")
                        .with(authentication(auth(user))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalDays").value(45))
                .andExpect(jsonPath("$.days.length()").value(20));
        Duration elapsed = Duration.ofNanos(System.nanoTime() - startedAt);

        assertThat(elapsed).isLessThan(STATEMENT_LATENCY_BUDGET);
    }
}
