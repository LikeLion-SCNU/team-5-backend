package org.example.naeilbank.controller;

import lombok.RequiredArgsConstructor;
import org.example.naeilbank.domain.evidence.EvidenceDtos.LedgerEvidenceView;
import org.example.naeilbank.domain.evidence.EvidenceService;
import org.example.naeilbank.global.exception.AuthException;
import org.example.naeilbank.global.exception.ErrorCode;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/ledger")
@RequiredArgsConstructor
public class LedgerEvidenceController {
    private final EvidenceService evidenceService;

    @GetMapping("/{entryId}/evidence")
    public ResponseEntity<LedgerEvidenceView> evidence(
            Authentication authentication,
            @PathVariable long entryId
    ) {
        return ResponseEntity.ok(evidenceService.ledgerEvidence(authenticatedUserId(authentication), entryId));
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
