package org.example.naeilbank.domain.conversion;

public enum ConversionSourceType {
    HEALTH_DAILY("health_daily"),
    MEAL_ITEM("meal_item");

    private final String persistedValue;

    ConversionSourceType(String persistedValue) {
        this.persistedValue = persistedValue;
    }

    public String persistedValue() {
        return persistedValue;
    }
}
