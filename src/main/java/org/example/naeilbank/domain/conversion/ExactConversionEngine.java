package org.example.naeilbank.domain.conversion;

import org.example.naeilbank.domain.conversion.ConversionModels.ConversionInput;
import org.example.naeilbank.domain.conversion.ConversionModels.ExactResult;
import org.example.naeilbank.domain.conversion.ConversionModels.RuleTerms;
import org.example.naeilbank.global.exception.AuthException;
import org.example.naeilbank.global.exception.ErrorCode;

import java.math.BigDecimal;
import java.math.RoundingMode;

public final class ExactConversionEngine {
    static final int CALCULATION_SCALE = 12;
    static final int SNAPSHOT_SCALE = 6;
    static final RoundingMode ROUNDING = RoundingMode.HALF_EVEN;
    private static final BigDecimal MAX_INPUT = new BigDecimal("1000000000");
    private static final BigDecimal MAX_SECONDS = new BigDecimal("31536000000");
    private static final BigDecimal SECONDS_PER_MINUTE = new BigDecimal("60");
    private static final long MAX_RATE_MINUTES = 525_600L;

    public ExactResult calculate(ConversionInput input, RuleTerms rule) {
        requireAvailable(input, rule);
        BigDecimal value = requireBoundedInput(input.value());
        BigDecimal normalized = value.divide(rule.unit().divisor(), CALCULATION_SCALE, ROUNDING);
        BigDecimal exactSeconds = normalized
                .multiply(BigDecimal.valueOf(rule.minutesPerUnit()))
                .multiply(SECONDS_PER_MINUTE)
                .setScale(SNAPSHOT_SCALE, ROUNDING);
        if (exactSeconds.abs().compareTo(MAX_SECONDS) > 0) {
            throw new AuthException(ErrorCode.CONVERSION_VALUE_OUT_OF_RANGE);
        }
        long postedSeconds = exactSeconds.setScale(0, ROUNDING).longValueExact();
        int ledgerMinutes = exactSeconds.divide(SECONDS_PER_MINUTE, 0, ROUNDING).intValueExact();
        return new ExactResult(normalized.setScale(SNAPSHOT_SCALE, ROUNDING), exactSeconds,
                postedSeconds, ledgerMinutes);
    }

    private void requireAvailable(ConversionInput input, RuleTerms rule) {
        if (input == null || input.category() == null || input.unit() == null || input.value() == null) {
            throw new AuthException(ErrorCode.CONVERSION_VALUE_OUT_OF_RANGE);
        }
        if (rule == null || !rule.active() || !rule.sourceActive() || rule.minutesPerUnit() == 0) {
            throw new AuthException(ErrorCode.CONVERSION_RULE_UNAVAILABLE);
        }
        if (Math.abs((long) rule.minutesPerUnit()) > MAX_RATE_MINUTES) {
            throw new AuthException(ErrorCode.CONVERSION_VALUE_OUT_OF_RANGE);
        }
        if (input.category() != rule.category()) {
            throw new AuthException(ErrorCode.CONVERSION_RULE_UNAVAILABLE);
        }
        if (input.unit() != rule.unit()) {
            throw new AuthException(ErrorCode.UNSUPPORTED_CONVERSION_UNIT);
        }
    }

    BigDecimal requireBoundedInput(BigDecimal value) {
        if (value == null || value.scale() < 0 || value.scale() > CALCULATION_SCALE
                || value.precision() > 22 || (long) value.precision() - value.scale() > 10
                || value.signum() < 0 || value.compareTo(MAX_INPUT) > 0) {
            throw new AuthException(ErrorCode.CONVERSION_VALUE_OUT_OF_RANGE);
        }
        return value.setScale(CALCULATION_SCALE);
    }
}
