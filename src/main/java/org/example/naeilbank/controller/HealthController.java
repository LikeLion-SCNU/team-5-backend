package org.example.naeilbank.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.example.naeilbank.domain.health.HealthDtos.HealthDailyResponse;
import org.example.naeilbank.domain.health.HealthDtos.UpsertHealthDailyRequest;
import org.example.naeilbank.domain.health.HealthService;
import org.example.naeilbank.global.exception.AuthException;
import org.example.naeilbank.global.exception.ErrorCode;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/health")
@RequiredArgsConstructor
public class HealthController {
    private final HealthService healthService;

    @PutMapping("/daily")
    public ResponseEntity<HealthDailyResponse> upsert(
            Authentication authentication,
            @Valid @RequestBody UpsertHealthDailyRequest request
    ) {
        return ResponseEntity.ok(healthService.upsert(authenticatedUserId(authentication), request));
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
