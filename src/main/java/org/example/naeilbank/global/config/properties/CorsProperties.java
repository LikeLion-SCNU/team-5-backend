package org.example.naeilbank.global.config.properties;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.util.List;

@Validated
@ConfigurationProperties(prefix = "app.cors")
public record CorsProperties(
        boolean allowCredentials,
        @NotEmpty List<@NotBlank String> allowedOrigins,
        @NotEmpty List<@NotBlank String> allowedMethods,
        @NotEmpty List<@NotBlank String> allowedHeaders,
        List<@NotBlank String> exposedHeaders
) {

    @AssertTrue(message = "credentialed CORS cannot use wildcard origins")
    public boolean isCredentialedOriginSafe() {
        return !allowCredentials || allowedOrigins != null && allowedOrigins.stream().noneMatch("*"::equals);
    }
}
