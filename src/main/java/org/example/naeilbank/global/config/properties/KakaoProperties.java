package org.example.naeilbank.global.config.properties;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "kakao")
public record KakaoProperties(
        @NotBlank String clientId,
        @NotBlank String redirectUri,
        @NotBlank String clientSecret,
        @NotBlank String tokenUri,
        @NotBlank String userInfoUri
) {
}
