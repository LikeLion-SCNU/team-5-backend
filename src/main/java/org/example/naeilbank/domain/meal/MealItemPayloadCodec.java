package org.example.naeilbank.domain.meal;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.naeilbank.domain.conversion.ConversionUnit;
import org.example.naeilbank.domain.conversion.HabitCategory;
import org.example.naeilbank.domain.meal.MealAnalysisContract.AnalyzedItem;
import org.example.naeilbank.domain.meal.MealDtos.MealItemView;
import org.example.naeilbank.domain.meal.MealDtos.UserMealItem;
import org.example.naeilbank.entity.MealItem;
import org.example.naeilbank.global.exception.AuthException;
import org.example.naeilbank.global.exception.ErrorCode;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

@Component
public class MealItemPayloadCodec {
    private final ObjectMapper objectMapper;

    public MealItemPayloadCodec(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    MealItem aiItem(java.util.UUID mealRecordId, AnalyzedItem item) {
        return new MealItem(mealRecordId, item.foodName(), encode(item.portion(), item.category(),
                item.unit(), item.value(), item.eligibility()), 0, false);
    }

    MealItem userItem(java.util.UUID mealRecordId, UserMealItem item) {
        return new MealItem(mealRecordId, item.foodName(), encode(item.portion(), item.category(),
                item.unit(), item.value(), normalize(item.eligibility())), 0, true);
    }

    MealItemView view(MealItem item) {
        StoredPortion portion = decode(item.getPortion());
        return new MealItemView(item.getId(), item.getFoodName(), portion.label(),
                portion.category(), portion.unit(), portion.value(), portion.eligibility(), item.isDeleted(),
                item.isUserAdded(), item.getRuleId());
    }

    StoredPortion decode(String raw) {
        try {
            StoredPortion portion = objectMapper.readValue(raw, StoredPortion.class);
            StoredPortion normalized = new StoredPortion(portion.label(), portion.category(), portion.unit(),
                    portion.value(), normalize(portion.eligibility()));
            validate(normalized.category(), normalized.unit(), normalized.value(), normalized.eligibility());
            return normalized;
        } catch (Exception e) {
            throw new AuthException(ErrorCode.INVALID_MEAL_REQUEST);
        }
    }

    private String encode(String label, HabitCategory category, ConversionUnit unit, BigDecimal value,
                          MealEligibility eligibility) {
        validate(category, unit, value, eligibility);
        try {
            return objectMapper.writeValueAsString(new StoredPortion(
                    label == null ? "" : label,
                    category,
                    unit,
                    value.stripTrailingZeros(),
                    eligibility
            ));
        } catch (Exception e) {
            throw new IllegalStateException("Could not encode meal item payload", e);
        }
    }

    private void validate(HabitCategory category, ConversionUnit unit, BigDecimal value,
                          MealEligibility eligibility) {
        if (category == null || unit == null || value == null || value.signum() <= 0) {
            throw new AuthException(ErrorCode.INVALID_MEAL_REQUEST);
        }
        if (category == HabitCategory.FOOD && unit != ConversionUnit.PER_SERVING) {
            throw new AuthException(ErrorCode.INVALID_MEAL_REQUEST);
        }
        if (category == HabitCategory.ALCOHOL && unit != ConversionUnit.PER_DRINK) {
            throw new AuthException(ErrorCode.INVALID_MEAL_REQUEST);
        }
        if (category != HabitCategory.FOOD && category != HabitCategory.ALCOHOL) {
            throw new AuthException(ErrorCode.INVALID_MEAL_REQUEST);
        }
        if (eligibility == null || category == HabitCategory.ALCOHOL
                && eligibility != MealEligibility.NEUTRAL) {
            throw new AuthException(ErrorCode.INVALID_MEAL_REQUEST);
        }
    }

    private MealEligibility normalize(MealEligibility eligibility) {
        return eligibility == null ? MealEligibility.NEUTRAL : eligibility;
    }

    @JsonIgnoreProperties(ignoreUnknown = false)
    record StoredPortion(
            String label,
            HabitCategory category,
            ConversionUnit unit,
            @JsonProperty("value") BigDecimal value,
            MealEligibility eligibility
    ) {
        boolean qualifiesForFoodCredit() {
            return category == HabitCategory.FOOD
                    && eligibility == MealEligibility.FRUIT_OR_VEGETABLE;
        }
    }
}
