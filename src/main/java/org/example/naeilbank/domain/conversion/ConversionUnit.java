package org.example.naeilbank.domain.conversion;

import org.example.naeilbank.global.exception.AuthException;
import org.example.naeilbank.global.exception.ErrorCode;

import java.math.BigDecimal;

public enum ConversionUnit {
    PER_UNIT("per_unit", BigDecimal.ONE),
    PER_MINUTE("per_minute", BigDecimal.ONE),
    PER_HOUR("per_hour", BigDecimal.ONE),
    PER_1000_STEPS("per_1000_steps", new BigDecimal("1000")),
    PER_SERVING("per_serving", BigDecimal.ONE),
    PER_DRINK("per_drink", BigDecimal.ONE);

    private final String persistedValue;
    private final BigDecimal divisor;

    ConversionUnit(String persistedValue, BigDecimal divisor) {
        this.persistedValue = persistedValue;
        this.divisor = divisor;
    }

    public String persistedValue() {
        return persistedValue;
    }

    BigDecimal divisor() {
        return divisor;
    }

    public static ConversionUnit parse(String value) {
        for (ConversionUnit unit : values()) {
            if (unit.persistedValue.equals(value)) {
                return unit;
            }
        }
        throw new AuthException(ErrorCode.UNSUPPORTED_CONVERSION_UNIT);
    }
}
