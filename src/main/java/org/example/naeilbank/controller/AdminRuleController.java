package org.example.naeilbank.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.example.naeilbank.domain.evidence.EvidenceDtos.ActivationRequest;
import org.example.naeilbank.domain.evidence.EvidenceDtos.CreateRuleRequest;
import org.example.naeilbank.domain.evidence.EvidenceDtos.RuleView;
import org.example.naeilbank.domain.evidence.EvidenceDtos.VersionRuleRequest;
import org.example.naeilbank.domain.evidence.EvidenceService;
import org.example.naeilbank.global.exception.AuthException;
import org.example.naeilbank.global.exception.ErrorCode;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/admin/rules")
@RequiredArgsConstructor
public class AdminRuleController {
    private final EvidenceService evidenceService;

    @PostMapping
    public ResponseEntity<RuleView> create(
            Authentication authentication,
            @Valid @RequestBody CreateRuleRequest request
    ) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(evidenceService.createRule(userId(authentication), request));
    }

    @PostMapping("/{ruleId}/versions")
    public ResponseEntity<RuleView> version(
            Authentication authentication,
            @PathVariable UUID ruleId,
            @Valid @RequestBody VersionRuleRequest request
    ) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(evidenceService.versionRule(userId(authentication), ruleId, request));
    }

    @PutMapping("/{ruleId}/activation")
    public ResponseEntity<RuleView> activate(
            Authentication authentication,
            @PathVariable UUID ruleId,
            @Valid @RequestBody ActivationRequest request
    ) {
        return ResponseEntity.ok(evidenceService.activateRule(userId(authentication), ruleId, request));
    }

    private UUID userId(Authentication authentication) {
        try {
            return UUID.fromString(authentication.getPrincipal().toString());
        } catch (RuntimeException e) {
            throw new AuthException(ErrorCode.AUTHENTICATION_REQUIRED);
        }
    }
}
