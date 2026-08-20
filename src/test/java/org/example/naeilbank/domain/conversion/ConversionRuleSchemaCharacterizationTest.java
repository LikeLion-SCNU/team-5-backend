package org.example.naeilbank.domain.conversion;

import org.example.naeilbank.entity.ConversionRule;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class ConversionRuleSchemaCharacterizationTest {
    @Test
    void persistedRuleShapeKeepsIntegerMinutesFreeTextUnitAndJsonCondition() {
        UUID sourceId = UUID.randomUUID();
        Instant now = Instant.parse("2026-08-20T00:00:00Z");

        ConversionRule rule = ConversionRule.create(ConversionRule.HabitType.activity,
                "TEST_FIXTURE activity rule", "{\"fixture\":true}", 7,
                "per_1000_steps", sourceId, true, now);

        assertThat(rule.getHabitType()).isEqualTo(ConversionRule.HabitType.activity);
        assertThat(rule.getMinutesDelta()).isEqualTo(7);
        assertThat(rule.getUnit()).isEqualTo("per_1000_steps");
        assertThat(rule.getConditionJson()).isEqualTo("{\"fixture\":true}");
        assertThat(rule.getSourceId()).isEqualTo(sourceId);
        assertThat(rule.isActive()).isTrue();
    }
}
