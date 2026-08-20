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
        @NotBlank String faceModel,
        @NotNull Duration timeout,
        @NotNull URI responsesUri
) {
    public OpenAiProperties {
        if (responsesUri == null) {
            responsesUri = URI.create("https://api.openai.com/v1/responses");
        }
    }
}
