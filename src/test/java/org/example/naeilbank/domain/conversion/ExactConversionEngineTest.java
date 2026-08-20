package org.example.naeilbank.domain.conversion;

import org.example.naeilbank.domain.conversion.ConversionModels.ConversionInput;
import org.example.naeilbank.domain.conversion.ConversionModels.RuleTerms;
import org.example.naeilbank.global.exception.AuthException;
import org.example.naeilbank.global.exception.ErrorCode;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertTimeout;

class ExactConversionEngineTest {
    private final ExactConversionEngine engine = new ExactConversionEngine();

    @Test
    void convertsFixtureRateToWholeSecondsWithExplicitHalfEvenRounding() {
        var result = engine.calculate(input("2.5", ConversionUnit.PER_UNIT),
                rule(7, ConversionUnit.PER_UNIT));

        assertThat(result.normalizedUnits()).isEqualByComparingTo("2.500000");
        assertThat(result.exactSeconds()).isEqualByComparingTo("1050.000000");
        assertThat(result.postedSeconds()).isEqualTo(1050L);
        assertThat(result.ledgerMinutes()).isEqualTo(18);
    }

    @Test
    void normalizesThousandStepFixtureWithoutBinaryFloatingPoint() {
        var result = engine.calculate(input("2500", ConversionUnit.PER_1000_STEPS),
                rule(7, ConversionUnit.PER_1000_STEPS));

        assertThat(result.normalizedUnits()).isEqualByComparingTo("2.500000");
        assertThat(result.postedSeconds()).isEqualTo(1050L);
    }

    @Test
    void preservesNegativeRuleSignAndUsesHalfEvenAtBothBoundaries() {
        var negative = engine.calculate(input("0.025", ConversionUnit.PER_UNIT),
                rule(-1, ConversionUnit.PER_UNIT));
        var tieDown = engine.calculate(input("0.008333333333", ConversionUnit.PER_UNIT),
                rule(1, ConversionUnit.PER_UNIT));

        assertThat(negative.postedSeconds()).isEqualTo(-2L);
        assertThat(negative.ledgerMinutes()).isZero();
        assertThat(tieDown.postedSeconds()).isZero();
    }

    @Test
    void positiveRateIsMonotonicForSupportedUnit() {
        long lower = engine.calculate(input("1.25", ConversionUnit.PER_HOUR),
                rule(3, ConversionUnit.PER_HOUR)).postedSeconds();
        long higher = engine.calculate(input("1.50", ConversionUnit.PER_HOUR),
                rule(3, ConversionUnit.PER_HOUR)).postedSeconds();

        assertThat(higher).isGreaterThan(lower);
    }

    @Test
    void mismatchedCategoryUnitInactiveAndExtremeInputsFailClosed() {
        assertError(() -> engine.calculate(input("1", ConversionUnit.PER_HOUR),
                rule(1, ConversionUnit.PER_UNIT)), ErrorCode.UNSUPPORTED_CONVERSION_UNIT);
        assertError(() -> engine.calculate(input("1", ConversionUnit.PER_UNIT),
                new RuleTerms(HabitCategory.SLEEP, ConversionUnit.PER_UNIT, 1, false, true)),
                ErrorCode.CONVERSION_RULE_UNAVAILABLE);
        assertError(() -> engine.calculate(input("1000000001", ConversionUnit.PER_UNIT),
                rule(525600, ConversionUnit.PER_UNIT)), ErrorCode.CONVERSION_VALUE_OUT_OF_RANGE);
        assertError(() -> engine.calculate(input("-1", ConversionUnit.PER_UNIT),
                rule(1, ConversionUnit.PER_UNIT)), ErrorCode.CONVERSION_VALUE_OUT_OF_RANGE);
        assertError(() -> engine.calculate(input("1", ConversionUnit.PER_UNIT),
                rule(525601, ConversionUnit.PER_UNIT)), ErrorCode.CONVERSION_VALUE_OUT_OF_RANGE);
        assertError(() -> ConversionUnit.parse("per_century"), ErrorCode.UNSUPPORTED_CONVERSION_UNIT);
    }

    @Test
    void hugePositiveAndNegativeScalesFailBeforePlainStringExpansion() {
        assertTimeout(Duration.ofSeconds(1), () -> {
            assertError(() -> engine.calculate(
                    new ConversionInput(HabitCategory.SLEEP, ConversionUnit.PER_UNIT,
                            new BigDecimal(BigInteger.ONE, Integer.MIN_VALUE)),
                    rule(1, ConversionUnit.PER_UNIT)), ErrorCode.CONVERSION_VALUE_OUT_OF_RANGE);
            assertError(() -> engine.calculate(
                    new ConversionInput(HabitCategory.SLEEP, ConversionUnit.PER_UNIT,
                            new BigDecimal(BigInteger.ONE, Integer.MAX_VALUE)),
                    rule(1, ConversionUnit.PER_UNIT)), ErrorCode.CONVERSION_VALUE_OUT_OF_RANGE);
            assertError(() -> engine.calculate(input("9".repeat(1000), ConversionUnit.PER_UNIT),
                    rule(1, ConversionUnit.PER_UNIT)), ErrorCode.CONVERSION_VALUE_OUT_OF_RANGE);
        });
    }

    private ConversionInput input(String value, ConversionUnit unit) {
        return new ConversionInput(HabitCategory.SLEEP, unit, new BigDecimal(value));
    }

    private RuleTerms rule(int minutes, ConversionUnit unit) {
        return new RuleTerms(HabitCategory.SLEEP, unit, minutes, true, true);
    }

    private void assertError(Runnable action, ErrorCode errorCode) {
        assertThatThrownBy(action::run)
                .isInstanceOfSatisfying(AuthException.class,
                        error -> assertThat(error.getErrorCode()).isEqualTo(errorCode));
    }
}
