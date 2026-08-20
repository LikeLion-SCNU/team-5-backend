package org.example.naeilbank.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.example.naeilbank.domain.plan.PlanDtos.PlanDecisionRequest;
import org.example.naeilbank.domain.plan.PlanDtos.PlanResponse;
import org.example.naeilbank.domain.plan.PlanService;
import org.example.naeilbank.global.exception.AuthException;
import org.example.naeilbank.global.exception.ErrorCode;
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
@RequestMapping("/api/v1/plans")
@RequiredArgsConstructor
public class PlanController {
    private final PlanService planService;

    @GetMapping("/current")
    public ResponseEntity<PlanResponse> current(Authentication authentication) {
        return ResponseEntity.ok(planService.current(authenticatedUserId(authentication)));
    }

    @PostMapping("/{planId}/decision")
    public ResponseEntity<PlanResponse> decide(
            Authentication authentication,
            @PathVariable UUID planId,
            @Valid @RequestBody PlanDecisionRequest request
    ) {
        return ResponseEntity.ok(planService.decide(authenticatedUserId(authentication), planId, request));
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
