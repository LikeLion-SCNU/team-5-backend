package org.example.naeilbank.global.config.properties;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.unit.DataSize;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "app.upload")
public record UploadProperties(
        @NotNull DataSize maxInputSize,
        @NotNull DataSize maxGeneratedSize
) {

    @AssertTrue(message = "max-input-size must be positive")
    public boolean isMaxInputSizePositive() {
        return maxInputSize != null && maxInputSize.toBytes() > 0;
    }

    @AssertTrue(message = "max-generated-size must be positive")
    public boolean isMaxGeneratedSizePositive() {
        return maxGeneratedSize != null && maxGeneratedSize.toBytes() > 0;
    }
}
