package org.example.naeilbank.controller;

import lombok.RequiredArgsConstructor;
import org.example.naeilbank.domain.protection.ProtectionDtos.ProtectionStatusResponse;
import org.example.naeilbank.domain.protection.ProtectionDtos.ProtectionModeRequest;
import org.example.naeilbank.domain.protection.ProtectionService;
import org.example.naeilbank.global.exception.AuthException;
import org.example.naeilbank.global.exception.ErrorCode;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/protection")
@RequiredArgsConstructor
public class ProtectionController {
    private final ProtectionService protectionService;

    @GetMapping
    public ResponseEntity<ProtectionStatusResponse> status(Authentication authentication) {
        return ResponseEntity.ok(protectionService.status(authenticatedUserId(authentication)));
    }

    @PostMapping("/proposals/{proposalId}/accept")
    public ResponseEntity<ProtectionStatusResponse> accept(Authentication authentication, @PathVariable UUID proposalId) {
        return ResponseEntity.ok(protectionService.accept(authenticatedUserId(authentication), proposalId));
    }

    @PostMapping("/proposals/{proposalId}/reject")
    public ResponseEntity<ProtectionStatusResponse> reject(Authentication authentication, @PathVariable UUID proposalId) {
        return ResponseEntity.ok(protectionService.reject(authenticatedUserId(authentication), proposalId));
    }

    @PutMapping("/mode")
    public ResponseEntity<ProtectionStatusResponse> setMode(
            Authentication authentication,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @jakarta.validation.Valid @RequestBody ProtectionModeRequest request
    ) {
        return ResponseEntity.ok(protectionService.setMode(authenticatedUserId(authentication), request.enabled(), idempotencyKey));
    }

    @DeleteMapping
    public ResponseEntity<ProtectionStatusResponse> disable(Authentication authentication) {
        return ResponseEntity.ok(protectionService.disable(authenticatedUserId(authentication)));
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
