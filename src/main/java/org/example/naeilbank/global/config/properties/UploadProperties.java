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
        @NotNull DataSize maxRequestSize,
        @NotNull DataSize maxGeneratedSize
) {
    private static final long MAX_INPUT_BYTES = 10L * 1024 * 1024;
    private static final long MAX_REQUEST_BYTES = 11L * 1024 * 1024;
    private static final long MAX_GENERATED_BYTES = 20L * 1024 * 1024;

    @AssertTrue(message = "max-input-size must be positive")
    public boolean isMaxInputSizePositive() {
        return maxInputSize != null && maxInputSize.toBytes() > 0;
    }

    @AssertTrue(message = "max-input-size must not exceed 10 MiB")
    public boolean isMaxInputSizeBounded() {
        return maxInputSize != null && maxInputSize.toBytes() <= MAX_INPUT_BYTES;
    }

    @AssertTrue(message = "max-request-size must accommodate input and not exceed 11 MiB")
    public boolean isMaxRequestSizeBounded() {
        return maxRequestSize != null
                && maxInputSize != null
                && maxRequestSize.toBytes() > maxInputSize.toBytes()
                && maxRequestSize.toBytes() <= MAX_REQUEST_BYTES;
    }

    @AssertTrue(message = "max-generated-size must be positive")
    public boolean isMaxGeneratedSizePositive() {
        return maxGeneratedSize != null && maxGeneratedSize.toBytes() > 0;
    }

    @AssertTrue(message = "max-generated-size must not exceed 20 MiB")
    public boolean isMaxGeneratedSizeBounded() {
        return maxGeneratedSize != null && maxGeneratedSize.toBytes() <= MAX_GENERATED_BYTES;
    }
}
