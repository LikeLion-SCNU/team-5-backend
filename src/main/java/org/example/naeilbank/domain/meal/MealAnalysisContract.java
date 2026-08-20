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
                    || item.value().compareTo(new BigDecimal("1000")) > 0) {
                throw new AuthException(ErrorCode.INVALID_MEAL_ANALYSIS);
            }
            if (item.category() == HabitCategory.FOOD && item.unit() != ConversionUnit.PER_SERVING) {
                throw new AuthException(ErrorCode.INVALID_MEAL_ANALYSIS);
            }
            if (item.category() == HabitCategory.ALCOHOL && item.unit() != ConversionUnit.PER_DRINK) {
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
            BigDecimal value
    ) {
    }
}
