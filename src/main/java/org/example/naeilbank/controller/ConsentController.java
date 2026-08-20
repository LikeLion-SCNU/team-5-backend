package org.example.naeilbank.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.example.naeilbank.domain.consent.ConsentDtos.ChangeRequest;
import org.example.naeilbank.domain.consent.ConsentDtos.ConsentListResponse;
import org.example.naeilbank.domain.consent.ConsentDtos.ConsentStatus;
import org.example.naeilbank.domain.model.entity.Consent;
import org.example.naeilbank.global.exception.AuthException;
import org.example.naeilbank.global.exception.ErrorCode;
import org.example.naeilbank.service.ConsentService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/consents")
@RequiredArgsConstructor
public class ConsentController {
    private final ConsentService consentService;

    @GetMapping
    public ResponseEntity<ConsentListResponse> statuses(Authentication authentication) {
        return ResponseEntity.ok(consentService.getStatuses(authenticatedUserId(authentication)));
    }

    @PutMapping("/{purpose}")
    public ResponseEntity<ConsentStatus> change(
            Authentication authentication,
            @PathVariable String purpose,
            @Valid @RequestBody ChangeRequest request
    ) {
        return ResponseEntity.ok(consentService.change(
                authenticatedUserId(authentication),
                parsePurpose(purpose),
                request
        ));
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

    private Consent.Purpose parsePurpose(String rawPurpose) {
        try {
            return Consent.Purpose.valueOf(rawPurpose);
        } catch (IllegalArgumentException e) {
            throw new AuthException(ErrorCode.INVALID_CONSENT_PURPOSE);
        }
    }
}
