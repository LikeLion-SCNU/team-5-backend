package org.example.naeilbank.domain.meal;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.naeilbank.domain.conversion.ConversionUnit;
import org.example.naeilbank.domain.conversion.HabitCategory;
import org.example.naeilbank.global.exception.AuthException;
import org.example.naeilbank.global.exception.ErrorCode;

import java.math.BigDecimal;
import java.util.List;

public final class MealAnalysisContract {
    /** 한 항목이 가질 수 있는 최대 인분 수 — 모델이 그램을 넣어도 원장이 폭주하지 않도록 막는다. */
    private static final BigDecimal MAX_SERVINGS_PER_ITEM = new BigDecimal("20");

    private static final int MAX_ITEMS = 30;

    private MealAnalysisContract() {
    }

    static AnalyzedMeal parse(ObjectMapper objectMapper, String json) {
        try {
            ObjectMapper strictMapper = objectMapper.copy()
                    .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, true);
            AnalyzedMeal meal = strictMapper.readValue(json, AnalyzedMeal.class);
            validate(meal);
            return meal;
        } catch (Exception e) {
            throw new AuthException(ErrorCode.INVALID_MEAL_ANALYSIS);
        }
    }

    static void validate(AnalyzedMeal meal) {
        if (meal == null || meal.items() == null || meal.items().size() > MAX_ITEMS) {
            throw new AuthException(ErrorCode.INVALID_MEAL_ANALYSIS);
        }
        for (AnalyzedItem item : meal.items()) {
            if (item.foodName() == null || item.foodName().isBlank()
                    || item.category() == null || item.unit() == null
                    || item.value() == null || item.value().signum() <= 0
                    || item.value().compareTo(MAX_SERVINGS_PER_ITEM) > 0) {
                throw new AuthException(ErrorCode.INVALID_MEAL_ANALYSIS);
            }
            if (item.category() == HabitCategory.FOOD && item.unit() != ConversionUnit.PER_SERVING) {
                throw new AuthException(ErrorCode.INVALID_MEAL_ANALYSIS);
            }
            if (item.category() == HabitCategory.ALCOHOL && item.unit() != ConversionUnit.PER_DRINK) {
                throw new AuthException(ErrorCode.INVALID_MEAL_ANALYSIS);
            }
            if (item.eligibility() == null
                    || item.category() == HabitCategory.ALCOHOL
                    && item.eligibility() != MealEligibility.NEUTRAL
                    || item.category() != HabitCategory.FOOD
                    && item.category() != HabitCategory.ALCOHOL) {
                throw new AuthException(ErrorCode.INVALID_MEAL_ANALYSIS);
            }
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = false)
    public record AnalyzedMeal(List<AnalyzedItem> items) {
    }

    @JsonIgnoreProperties(ignoreUnknown = false)
    public record AnalyzedItem(
            @JsonProperty("food_name") String foodName,
            String portion,
            HabitCategory category,
            ConversionUnit unit,
            BigDecimal value,
            MealEligibility eligibility
    ) {
    }
}
