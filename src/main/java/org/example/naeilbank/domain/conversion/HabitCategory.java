package org.example.naeilbank.domain.conversion;

import org.example.naeilbank.entity.ConversionRule;

public enum HabitCategory {
    SLEEP(ConversionRule.HabitType.sleep),
    ACTIVITY(ConversionRule.HabitType.activity),
    SCREEN_TIME(ConversionRule.HabitType.screen_time),
    FOOD(ConversionRule.HabitType.food),
    ALCOHOL(ConversionRule.HabitType.alcohol);

    private final ConversionRule.HabitType persistedValue;

    HabitCategory(ConversionRule.HabitType persistedValue) {
        this.persistedValue = persistedValue;
    }

    public ConversionRule.HabitType persistedValue() {
        return persistedValue;
    }

    public static HabitCategory from(ConversionRule.HabitType value) {
        for (HabitCategory category : values()) {
            if (category.persistedValue == value) {
                return category;
            }
        }
        throw new IllegalArgumentException("Unsupported persisted habit category");
    }
}
