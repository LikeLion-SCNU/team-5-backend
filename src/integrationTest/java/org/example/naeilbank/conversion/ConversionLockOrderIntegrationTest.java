package org.example.naeilbank.conversion;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import org.example.naeilbank.domain.conversion.ConversionModels.ConversionCommand;
import org.example.naeilbank.domain.conversion.ConversionModels.ConversionReceipt;
import org.example.naeilbank.domain.conversion.ConversionService;
import org.example.naeilbank.domain.conversion.ConversionSourceType;
import org.example.naeilbank.domain.conversion.ConversionUnit;
import org.example.naeilbank.domain.conversion.HabitCategory;
import org.example.naeilbank.domain.evidence.EvidenceDtos.RuleContent;
import org.example.naeilbank.domain.evidence.EvidenceDtos.RuleView;
import org.example.naeilbank.domain.evidence.EvidenceDtos.VersionRuleRequest;
import org.example.naeilbank.domain.evidence.EvidenceService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.example.naeilbank.conversion.ConversionIntegrationFixtures.healthEvent;
import static org.example.naeilbank.conversion.ConversionIntegrationFixtures.user;

@Testcontainers
@ActiveProfiles("integration-test")
@SpringBootTest
class ConversionLockOrderIntegrationTest {
    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("conversion_lock_test").withUsername("naeil").withPassword("naeil_test");

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired ConversionService conversionService;
    @Autowired EvidenceService evidenceService;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    @AfterEach
    void deactivateFixtureRules() {
        jdbc.update("update conversion_rules set is_active = false where label like 'TEST_FIXTURE%' or logical_key::text like '21000000-%'");
    }

    @Test
    void concurrentConversionAndRuleVersioningShareRuleThenSourceLockOrderWithoutDeadlock()
            throws Exception {
        UUID userId = user(jdbc, "conversion-lock-user");
        UUID adminId = user(jdbc, "conversion-lock-admin");
        UUID eventId = healthEvent(jdbc, userId, LocalDate.of(2026, 8, 20));
        UUID sourceId = source();
        UUID v1 = rule(sourceId);
        ConversionCommand command = new ConversionCommand(eventId, ConversionSourceType.HEALTH_DAILY,
                HabitCategory.ACTIVITY, ConversionUnit.PER_1000_STEPS,
                new BigDecimal("2500"), LocalDate.of(2026, 8, 20));
        RuleContent v2Content = new RuleContent(org.example.naeilbank.entity.ConversionRule.HabitType.activity,
                "TEST_FIXTURE lock v2", JsonNodeFactory.instance.objectNode(), 8,
                "per_1000_steps", sourceId);
        CountDownLatch start = new CountDownLatch(1);

        try (var executor = Executors.newFixedThreadPool(2)) {
            var conversion = executor.submit(() -> {
                await(start);
                return conversionService.convert(userId, command);
            });
            var versioning = executor.submit(() -> {
                await(start);
                return evidenceService.versionRule(adminId, v1,
                        new VersionRuleRequest(v2Content, true, 1L));
            });
            start.countDown();
            ConversionReceipt receipt = conversion.get(10, TimeUnit.SECONDS);
            RuleView v2 = versioning.get(10, TimeUnit.SECONDS);

            assertThat(receipt.ruleId()).isIn(v1, v2.id());
            assertThat(receipt.postedSeconds()).isEqualTo(receipt.ruleId().equals(v1) ? 1050 : 1200);
            assertThat(jdbc.queryForObject(
                    "select count(*) from conversion_postings where user_id = ?", Integer.class, userId)).isOne();
            assertThat(jdbc.queryForObject(
                    "select count(*) from conversion_rules where id = ? and is_active", Integer.class, v2.id())).isOne();
        }
    }

    private UUID source() {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                insert into sources (id, logical_key, title, doi_url, summary_ko, scope_ko, limitations_ko)
                values (?, ?, 'TEST_FIXTURE lock source', ?, 'fixture', 'fixture', 'fixture')
                """, id, id, "https://example.test/fixture/" + id);
        return id;
    }

    private UUID rule(UUID sourceId) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                insert into conversion_rules (id, logical_key, habit_type, label, condition_json,
                    minutes_delta, unit, source_id) values (?, ?, 'activity',
                    'TEST_FIXTURE lock v1', '{}'::jsonb, 7, 'per_1000_steps', ?)
                """, id, id, sourceId);
        return id;
    }

    private void await(CountDownLatch latch) {
        try {
            assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Interrupted while awaiting lock-order test", exception);
        }
    }
}
