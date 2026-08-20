package org.example.naeilbank.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.example.naeilbank.domain.meal.MealDtos.ConfirmMealRequest;
import org.example.naeilbank.domain.meal.MealDtos.CreateMealRequest;
import org.example.naeilbank.domain.meal.MealDtos.MealView;
import org.example.naeilbank.domain.meal.MealService;
import org.example.naeilbank.global.exception.AuthException;
import org.example.naeilbank.global.exception.ErrorCode;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/meals")
@RequiredArgsConstructor
public class MealController {
    private final MealService mealService;

    @PostMapping
    public ResponseEntity<MealView> create(
            Authentication authentication,
            @Valid @RequestBody CreateMealRequest request
    ) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(mealService.create(authenticatedUserId(authentication), request));
    }

    @PostMapping("/{mealId}/analyze")
    public ResponseEntity<MealView> retry(Authentication authentication, @PathVariable UUID mealId) {
        return ResponseEntity.ok(mealService.retryAnalysis(authenticatedUserId(authentication), mealId));
    }

    @GetMapping("/{mealId}")
    public ResponseEntity<MealView> get(Authentication authentication, @PathVariable UUID mealId) {
        return ResponseEntity.ok(mealService.get(authenticatedUserId(authentication), mealId));
    }

    @PostMapping("/{mealId}/confirm")
    public ResponseEntity<MealView> confirm(
            Authentication authentication,
            @PathVariable UUID mealId,
            @Valid @RequestBody(required = false) ConfirmMealRequest request
    ) {
        return ResponseEntity.ok(mealService.confirm(authenticatedUserId(authentication), mealId, request));
    }

    @PostMapping("/{mealId}/exclude")
    public ResponseEntity<MealView> exclude(Authentication authentication, @PathVariable UUID mealId) {
        return ResponseEntity.ok(mealService.exclude(authenticatedUserId(authentication), mealId));
    }

    private UUID authenticatedUserId(Authentication authentication) {
        if (authentication == null || authentication.getPrincipal() == null) {
            throw new AuthException(ErrorCode.AUTHENTICATION_REQUIRED);
        }
        try {
            return UUID.fromString(authentication.getPrincipal().toString());
        } catch (IllegalArgumentException e) {
            throw new AuthException(ErrorCode.AUTHENTICATION_REQUIRED);
        }
    }
}
