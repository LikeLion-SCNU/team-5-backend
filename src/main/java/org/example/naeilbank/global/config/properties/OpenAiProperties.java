package org.example.naeilbank.global.config.properties;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.net.URI;
import java.time.Duration;

@Validated
@ConfigurationProperties(prefix = "openai")
public record OpenAiProperties(
        @NotBlank String apiKey,
        @NotBlank String mealModel,
        @NotBlank String imageModel,
        @NotNull Duration timeout,
        @NotNull URI responsesUri
) {
    public OpenAiProperties {
        if (responsesUri == null) {
            responsesUri = URI.create("https://api.openai.com/v1/responses");
        }
    }

    public URI baseUrl() {
        String value = responsesUri.toString();
        return value.endsWith("/responses")
                ? URI.create(value.substring(0, value.length() - "/responses".length()))
                : responsesUri;
    }
}
