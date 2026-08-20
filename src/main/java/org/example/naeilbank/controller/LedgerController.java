package org.example.naeilbank.controller;

import lombok.RequiredArgsConstructor;
import org.example.naeilbank.domain.ledger.LedgerDtos.BalanceResponse;
import org.example.naeilbank.domain.ledger.LedgerDtos.StatementResponse;
import org.example.naeilbank.domain.ledger.LedgerDtos.TrendResponse;
import org.example.naeilbank.domain.ledger.LedgerQueryService;
import org.example.naeilbank.global.exception.AuthException;
import org.example.naeilbank.global.exception.ErrorCode;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/ledger")
@RequiredArgsConstructor
public class LedgerController {
    private final LedgerQueryService ledgerQueryService;

    @GetMapping("/balance")
    public ResponseEntity<BalanceResponse> balance(Authentication authentication) {
        return ResponseEntity.ok(ledgerQueryService.balance(authenticatedUserId(authentication)));
    }

    @GetMapping("/statements")
    public ResponseEntity<StatementResponse> statements(
            Authentication authentication,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
            LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
            LocalDate to,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        return ResponseEntity.ok(ledgerQueryService.statements(authenticatedUserId(authentication),
                from, to, page, size));
    }

    @GetMapping("/trends/daily")
    public ResponseEntity<TrendResponse> dailyTrend(
            Authentication authentication,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
            LocalDate to
    ) {
        return ResponseEntity.ok(ledgerQueryService.dailyTrend(authenticatedUserId(authentication), to));
    }

    @GetMapping("/trends/weekly")
    public ResponseEntity<TrendResponse> weeklyTrend(
            Authentication authentication,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
            LocalDate to
    ) {
        return ResponseEntity.ok(ledgerQueryService.weeklyTrend(authenticatedUserId(authentication), to));
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
