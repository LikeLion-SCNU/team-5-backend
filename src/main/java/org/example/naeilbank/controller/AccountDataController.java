package org.example.naeilbank.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.example.naeilbank.domain.account.AccountDataDtos.DataSummaryResponse;
import org.example.naeilbank.domain.account.AccountDataDtos.DeleteResultResponse;
import org.example.naeilbank.domain.account.AccountDataDtos.DeleteScopesRequest;
import org.example.naeilbank.domain.account.AccountDataService;
import org.example.naeilbank.global.exception.AuthException;
import org.example.naeilbank.global.exception.ErrorCode;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/account")
@RequiredArgsConstructor
public class AccountDataController {
    private final AccountDataService accountDataService;

    @GetMapping("/data")
    public ResponseEntity<DataSummaryResponse> summary(Authentication authentication) {
        return ResponseEntity.ok(accountDataService.summary(authenticatedUserId(authentication)));
    }

    @PostMapping("/data/delete")
    public ResponseEntity<DeleteResultResponse> deleteScopes(
            Authentication authentication,
            @Valid @RequestBody DeleteScopesRequest request
    ) {
        return ResponseEntity.ok(
                accountDataService.deleteScopes(authenticatedUserId(authentication), request.scopes()));
    }

    @DeleteMapping
    public ResponseEntity<DeleteResultResponse> deleteAccount(Authentication authentication) {
        return ResponseEntity.ok(accountDataService.deleteAccount(authenticatedUserId(authentication)));
    }

    private UUID authenticatedUserId(Authentication authentication) {
        if (authentication == null || authentication.getPrincipal() == null) {
            throw new AuthException(ErrorCode.AUTHENTICATION_REQUIRED);
        }
        try {
            return UUID.fromString(authentication.getPrincipal().toString());
        } catch (IllegalArgumentException exception) {
            throw new AuthException(ErrorCode.AUTHENTICATION_REQUIRED);
        }
    }
}
