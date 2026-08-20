package org.example.naeilbank.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.example.naeilbank.domain.notification.NotificationDtos.PreferenceRequest;
import org.example.naeilbank.domain.notification.NotificationDtos.PreferenceResponse;
import org.example.naeilbank.domain.notification.NotificationDtos.PublicKeyResponse;
import org.example.naeilbank.domain.notification.NotificationDtos.SubscriptionRequest;
import org.example.naeilbank.domain.notification.NotificationDtos.SubscriptionResponse;
import org.example.naeilbank.domain.notification.NotificationService;
import org.example.naeilbank.global.exception.AuthException;
import org.example.naeilbank.global.exception.ErrorCode;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/notifications")
@RequiredArgsConstructor
public class NotificationController {
    private final NotificationService notificationService;

    @GetMapping("/vapid-public-key")
    public ResponseEntity<PublicKeyResponse> publicKey() {
        return ResponseEntity.ok(new PublicKeyResponse(notificationService.publicKey()));
    }

    @PostMapping("/subscriptions")
    public ResponseEntity<SubscriptionResponse> register(
            Authentication authentication,
            @Valid @RequestBody SubscriptionRequest request
    ) {
        return ResponseEntity.ok(notificationService.register(authenticatedUserId(authentication), request));
    }

    @PutMapping("/subscriptions/{subscriptionId}")
    public ResponseEntity<SubscriptionResponse> updateSubscription(
            Authentication authentication,
            @PathVariable UUID subscriptionId,
            @Valid @RequestBody SubscriptionRequest request
    ) {
        return ResponseEntity.ok(notificationService.updateSubscription(
                authenticatedUserId(authentication),
                subscriptionId,
                request
        ));
    }

    @DeleteMapping("/subscriptions/{subscriptionId}")
    public ResponseEntity<Void> revoke(Authentication authentication, @PathVariable UUID subscriptionId) {
        notificationService.revoke(authenticatedUserId(authentication), subscriptionId);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/preference")
    public ResponseEntity<PreferenceResponse> preference(Authentication authentication) {
        return ResponseEntity.ok(notificationService.preference(authenticatedUserId(authentication)));
    }

    @PutMapping("/preference")
    public ResponseEntity<PreferenceResponse> updatePreference(
            Authentication authentication,
            @Valid @RequestBody PreferenceRequest request
    ) {
        return ResponseEntity.ok(notificationService.updatePreference(authenticatedUserId(authentication), request));
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
