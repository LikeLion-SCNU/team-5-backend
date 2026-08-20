package org.example.naeilbank.ledger;

import org.example.naeilbank.domain.conversion.ConversionModels.ConversionCommand;
import org.example.naeilbank.domain.conversion.ConversionSourceType;
import org.example.naeilbank.domain.conversion.ConversionUnit;
import org.example.naeilbank.domain.conversion.HabitCategory;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
@ActiveProfiles("integration-test")
@SpringBootTest
@AutoConfigureMockMvc
class LedgerConversionIntegrationTest extends LedgerIntegrationSupport {
    @Test
    void conversionPostingIsConsistentWithLedgerBalanceUnderConcurrentReplay() throws Exception {
        UUID user = user("conversion-ledger", false);
        UUID rule = rule("activity", 7, "per_1000_steps");
        UUID event = healthEvent(user, LocalDate.of(2026, 8, 20));
        ConversionCommand command = new ConversionCommand(event, ConversionSourceType.HEALTH_DAILY,
                HabitCategory.ACTIVITY, ConversionUnit.PER_1000_STEPS,
                new BigDecimal("2500"), LocalDate.of(2026, 8, 20));

        CountDownLatch start = new CountDownLatch(1);
        Callable<ConversionOutcome> task = () -> {
            start.await(5, TimeUnit.SECONDS);
            try {
                return new ConversionOutcome(conversionService.convert(user, command), null);
            } catch (Throwable error) {
                return new ConversionOutcome(null, error);
            }
        };
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(task);
            var second = executor.submit(task);
            start.countDown();
            List<ConversionOutcome> outcomes = List.of(
                    first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS));
            assertThat(outcomes).allSatisfy(outcome -> assertThat(outcome.error()).isNull());
            assertThat(outcomes).extracting(outcome -> outcome.receipt().ledgerEntryId())
                    .containsOnly(outcomes.getFirst().receipt().ledgerEntryId());
        }
        assertThat(jdbc.queryForObject("select count(*) from ledger_entries where user_id = ?",
                Integer.class, user)).isOne();
        assertThat(jdbc.queryForObject("select total_minutes from v_balance where user_id = ?",
                Long.class, user)).isEqualTo(18);
    }
}
