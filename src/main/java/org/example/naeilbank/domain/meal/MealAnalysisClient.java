package org.example.naeilbank.domain.meal;

import org.example.naeilbank.domain.meal.MealAnalysisContract.AnalyzedMeal;

public interface MealAnalysisClient {
    AnalyzedMeal analyze(String contentType, byte[] imageBytes);
}
