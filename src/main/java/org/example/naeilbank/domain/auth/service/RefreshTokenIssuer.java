package org.example.naeilbank.domain.auth.service;

import org.springframework.stereotype.Component;

import java.security.SecureRandom;
import java.util.Base64;

@Component
public class RefreshTokenIssuer {
    private static final int TOKEN_BYTES = 48;

    private final SecureRandom secureRandom = new SecureRandom();

    public String issue() {
        byte[] bytes = new byte[TOKEN_BYTES];
        secureRandom.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
