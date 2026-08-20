package org.example.naeilbank.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.example.naeilbank.domain.evidence.EvidenceDtos.ActivationRequest;
import org.example.naeilbank.domain.evidence.EvidenceDtos.CreateSourceRequest;
import org.example.naeilbank.domain.evidence.EvidenceDtos.SourceView;
import org.example.naeilbank.domain.evidence.EvidenceDtos.VersionSourceRequest;
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
@RequestMapping("/api/admin/sources")
@RequiredArgsConstructor
public class AdminSourceController {
    private final EvidenceService evidenceService;

    @PostMapping
    public ResponseEntity<SourceView> create(
            Authentication authentication,
            @Valid @RequestBody CreateSourceRequest request
    ) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(evidenceService.createSource(userId(authentication), request));
    }

    @PostMapping("/{sourceId}/versions")
    public ResponseEntity<SourceView> version(
            Authentication authentication,
            @PathVariable UUID sourceId,
            @Valid @RequestBody VersionSourceRequest request
    ) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(evidenceService.versionSource(userId(authentication), sourceId, request));
    }

    @PutMapping("/{sourceId}/activation")
    public ResponseEntity<SourceView> activate(
            Authentication authentication,
            @PathVariable UUID sourceId,
            @Valid @RequestBody ActivationRequest request
    ) {
        return ResponseEntity.ok(evidenceService.activateSource(userId(authentication), sourceId, request));
    }

    private UUID userId(Authentication authentication) {
        try {
            return UUID.fromString(authentication.getPrincipal().toString());
        } catch (RuntimeException e) {
            throw new AuthException(ErrorCode.AUTHENTICATION_REQUIRED);
        }
    }
}
