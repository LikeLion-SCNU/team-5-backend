package org.example.naeilbank.domain.media;

import org.example.naeilbank.global.exception.AuthException;
import org.example.naeilbank.global.exception.ErrorCode;
import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.Set;

@Component
public class ImageValidator {
    static final long MAX_DIMENSION = 8192;
    static final long MAX_PIXELS = 40_000_000;
    private static final Set<String> SUPPORTED_TYPES = Set.of(
            "image/jpeg",
            "image/png",
            "image/webp"
    );
    private final BoundedImageDecoder boundedImageDecoder;

    public ImageValidator(BoundedImageDecoder boundedImageDecoder) {
        this.boundedImageDecoder = boundedImageDecoder;
    }

    public ValidatedImage validate(byte[] content, String declaredContentType, long maxBytes) {
        if (content == null || content.length == 0) {
            throw new AuthException(ErrorCode.INVALID_IMAGE);
        }
        if (content.length > maxBytes) {
            throw new AuthException(ErrorCode.MEDIA_TOO_LARGE);
        }
        String declared = normalizeContentType(declaredContentType);
        ParsedImage image = ImageHeaderParser.parse(content);
        if (!image.format().contentType().equals(declared)) {
            throw new AuthException(ErrorCode.MEDIA_TYPE_MISMATCH);
        }
        if (image.width() < 1 || image.height() < 1
                || image.width() > MAX_DIMENSION || image.height() > MAX_DIMENSION
                || image.width() * image.height() > MAX_PIXELS) {
            throw new AuthException(ErrorCode.INVALID_IMAGE);
        }
        boundedImageDecoder.verify(content, image.width(), image.height());
        return new ValidatedImage(declared, image.width(), image.height());
    }

    private String normalizeContentType(String declaredContentType) {
        if (declaredContentType == null) {
            throw new AuthException(ErrorCode.MEDIA_TYPE_UNSUPPORTED);
        }
        String normalized = declaredContentType.trim().toLowerCase(Locale.ROOT);
        if (!SUPPORTED_TYPES.contains(normalized)) {
            throw new AuthException(ErrorCode.MEDIA_TYPE_UNSUPPORTED);
        }
        return normalized;
    }

    public record ValidatedImage(String contentType, long width, long height) {
    }
}
