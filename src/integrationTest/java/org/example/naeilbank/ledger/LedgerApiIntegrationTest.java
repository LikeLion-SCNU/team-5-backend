package org.example.naeilbank.ledger;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Testcontainers
@ActiveProfiles("integration-test")
@SpringBootTest
@AutoConfigureMockMvc
public class LedgerApiIntegrationTest extends LedgerIntegrationSupport {

    @Test
    void emptyLedgerReturnsZeroBalanceAndNoStatementDays() throws Exception {
        UUID user = user("empty-ledger", false);

        mvc.perform(get("/api/v1/ledger/balance").with(authentication(auth(user))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.balanceMinutes").value(0))
                .andExpect(jsonPath("$.previousDayDeltaMinutes").value(0))
                .andExpect(jsonPath("$.timezone").value("Asia/Seoul"));

        mvc.perform(get("/api/v1/ledger/statements")
                        .param("from", "2026-08-01").param("to", "2026-08-20")
                        .with(authentication(auth(user))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalDays").value(0))
                .andExpect(jsonPath("$.hasNext").value(false))
                .andExpect(jsonPath("$.days.length()").value(0));
    }

    @Test
    void statementPaginationKeepsDayAndLineTieOrderingStable() throws Exception {
        UUID user = user("stable-page", false);
        UUID rule = rule();
        entry(user, LocalDate.of(2026, 8, 18), 1, rule, "health_daily");
        long first = entry(user, LocalDate.of(2026, 8, 19), 2, rule, "health_daily");
        long second = entry(user, LocalDate.of(2026, 8, 19), 3, rule, "health_daily");

        mvc.perform(get("/api/v1/ledger/statements")
                        .param("from", "2026-08-18").param("to", "2026-08-20")
                        .param("page", "0").param("size", "1")
                        .with(authentication(auth(user))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.hasNext").value(true))
                .andExpect(jsonPath("$.days[0].entryDate").value("2026-08-19"))
                .andExpect(jsonPath("$.days[0].lines[0].entryId").value(second))
                .andExpect(jsonPath("$.days[0].lines[1].entryId").value(first));
    }

    @Test
    void statementsBalanceTrendsAreTenantScopedAndProtectionInvariant() throws Exception {
        UUID user = user("ledger", false);
        UUID protectedUser = user("protected", true);
        UUID other = user("other", false);
        UUID rule = rule();
        entry(user, LocalDate.of(2026, 8, 18), 30, rule, "health_daily");
        entry(user, LocalDate.of(2026, 8, 19), -10, rule, "meal_item");
        entry(user, LocalDate.of(2026, 8, 19), 20, rule, "health_daily");
        entry(protectedUser, LocalDate.of(2026, 8, 19), -10, rule, "meal_item");
        entry(other, LocalDate.of(2026, 8, 19), 999, rule, "health_daily");

        mvc.perform(get("/api/v1/ledger/balance").with(authentication(auth(user))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.balanceMinutes").value(40))
                .andExpect(jsonPath("$.previousDayDeltaMinutes").value(10))
                .andExpect(jsonPath("$.asOfDate").value("2026-08-20"))
                .andExpect(jsonPath("$.timezone").value("Asia/Seoul"));

        mvc.perform(get("/api/v1/ledger/statements")
                        .param("from", "2026-08-18").param("to", "2026-08-20")
                        .param("page", "0").param("size", "20")
                        .with(authentication(auth(user))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalDays").value(2))
                .andExpect(jsonPath("$.days[0].entryDate").value("2026-08-19"))
                .andExpect(jsonPath("$.days[0].dailyNetMinutes").value(10))
                .andExpect(jsonPath("$.days[0].cumulativeBalanceMinutes").value(40))
                .andExpect(jsonPath("$.days[0].previousDayDeltaMinutes").value(30))
                .andExpect(jsonPath("$.days[0].lines.length()").value(2));

        mvc.perform(get("/api/v1/ledger/statements")
                        .param("from", "2026-08-19").param("to", "2026-08-19")
                        .with(authentication(auth(protectedUser))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.days[0].dailyNetMinutes").value(-10))
                .andExpect(jsonPath("$.days[0].lines[0].displayText").value("회복 조정 10분"))
                .andExpect(jsonPath("$.days[0].lines[0].minutesDelta").value(-10));

        mvc.perform(get("/api/v1/ledger/trends/daily")
                        .param("to", "2026-08-20").with(authentication(auth(user))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.points.length()").value(7))
                .andExpect(jsonPath("$.points[4].netMinutes").value(30))
                .andExpect(jsonPath("$.points[5].cumulativeBalanceMinutes").value(40));

        mvc.perform(get("/api/v1/ledger/trends/weekly")
                        .param("to", "2026-08-20").with(authentication(auth(user))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.points.length()").value(4))
                .andExpect(jsonPath("$.points[3].netMinutes").value(40));
    }

    @Test
    void appendOnlyTriggerBlocksUpdateAndDeleteWithoutCorrectionEndpoint() {
        UUID user = user("append-only", false);
        UUID rule = rule();
        long entryId = entry(user, LocalDate.of(2026, 8, 19), 15, rule, "health_daily");
        long beforeDigest = digest(user);

        assertThatThrownBy(() -> jdbc.update("update ledger_entries set minutes_delta = 1 where id = ?",
                entryId)).hasMessageContaining("ledger_entries is append-only");
        assertThatThrownBy(() -> jdbc.update("delete from ledger_entries where id = ?", entryId))
                .hasMessageContaining("ledger_entries is append-only");
        assertThat(digest(user)).isEqualTo(beforeDigest);
    }

    @Test
    void statementReadAndConcurrentAppendBothCompleteWithRepeatableReadSnapshot() throws Exception {
        UUID user = user("statement-snapshot", false);
        UUID rule = rule();
        entry(user, LocalDate.of(2026, 8, 18), 5, rule, "health_daily");
        CountDownLatch start = new CountDownLatch(1);
        Callable<RequestOutcome> read = () -> {
            start.await(5, TimeUnit.SECONDS);
            try {
                MvcResult result = mvc.perform(get("/api/v1/ledger/statements")
                                .param("from", "2026-08-18").param("to", "2026-08-20")
                                .with(authentication(auth(user))))
                        .andReturn();
                return new RequestOutcome(result.getResponse().getStatus(),
                        result.getResponse().getContentAsString(), null);
            } catch (Throwable error) {
                return new RequestOutcome(0, null, error);
            }
        };
        Callable<WriteOutcome> write = () -> {
            start.await(5, TimeUnit.SECONDS);
            try {
                return new WriteOutcome(entry(user, LocalDate.of(2026, 8, 19), 7, rule,
                        "health_daily"), null);
            } catch (Throwable error) {
                return new WriteOutcome(0L, error);
            }
        };

        try (var executor = Executors.newFixedThreadPool(2)) {
            var readFuture = executor.submit(read);
            var writeFuture = executor.submit(write);
            start.countDown();
            RequestOutcome readOutcome = readFuture.get(10, TimeUnit.SECONDS);
            WriteOutcome writeOutcome = writeFuture.get(10, TimeUnit.SECONDS);
            assertThat(readOutcome.error()).isNull();
            assertThat(writeOutcome.error()).isNull();
            assertThat(readOutcome.status()).isEqualTo(200);
            assertThat(writeOutcome.entryId()).isPositive();
            JsonNode response = objectMapper.readTree(readOutcome.body());
            boolean beforeAppend = response.path("totalDays").asLong() == 1
                    && response.path("days").size() == 1
                    && response.path("days").get(0).path("entryDate").asText().equals("2026-08-18")
                    && response.path("days").get(0).path("dailyNetMinutes").asLong() == 5
                    && response.path("days").get(0).path("cumulativeBalanceMinutes").asLong() == 5;
            boolean afterAppend = response.path("totalDays").asLong() == 2
                    && response.path("days").size() == 2
                    && response.path("days").get(0).path("entryDate").asText().equals("2026-08-19")
                    && response.path("days").get(0).path("dailyNetMinutes").asLong() == 7
                    && response.path("days").get(0).path("cumulativeBalanceMinutes").asLong() == 12
                    && response.path("days").get(1).path("cumulativeBalanceMinutes").asLong() == 5;
            assertThat(beforeAppend || afterAppend)
                    .as("statement response must be one complete database snapshot")
                    .isTrue();
        }
        assertThat(jdbc.queryForObject("select count(*) from ledger_entries where user_id = ?",
                Integer.class, user)).isEqualTo(2);
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class FixedClockConfig {
        @Bean
        @Primary
        Clock fixedClock() {
            return Clock.fixed(Instant.parse("2026-08-20T07:30:00Z"), ZoneOffset.UTC);
        }
    }
}
