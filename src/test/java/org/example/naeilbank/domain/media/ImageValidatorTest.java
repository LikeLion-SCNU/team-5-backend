package org.example.naeilbank.domain.media;

import org.example.naeilbank.global.exception.AuthException;
import org.example.naeilbank.global.exception.ErrorCode;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ImageValidatorTest {
    private final ImageValidator validator = new ImageValidator(new BoundedImageDecoder());

    @Test
    void acceptsBoundedJpegPngAndWebpHeaders() {
        assertImage(ImageTestFixtures.jpeg(640, 480), "image/jpeg", 640, 480);
        assertImage(ImageTestFixtures.png(800, 600), "image/png", 800, 600);
        assertImage(ImageTestFixtures.webp(), "image/webp", 1, 1);
    }

    @Test
    void decodesLargeSourceThroughBoundedSubsampling() {
        assertImage(ImageTestFixtures.png(2500, 2000), "image/png", 2500, 2000);
    }

    @Test
    void rejectsTruncatedJpegPngAndWebp() {
        assertInvalid(truncate(ImageTestFixtures.jpeg(10, 10)), "image/jpeg", ErrorCode.INVALID_IMAGE);
        assertInvalid(truncate(ImageTestFixtures.png(10, 10)), "image/png", ErrorCode.INVALID_IMAGE);
        assertInvalid(truncate(ImageTestFixtures.webp()), "image/webp", ErrorCode.INVALID_IMAGE);
    }

    @Test
    void rejectsDeclaredMimeSpoofingForEverySupportedFormat() {
        assertInvalid(ImageTestFixtures.jpeg(10, 10), "image/png", ErrorCode.MEDIA_TYPE_MISMATCH);
        assertInvalid(ImageTestFixtures.png(10, 10), "image/webp", ErrorCode.MEDIA_TYPE_MISMATCH);
        assertInvalid(ImageTestFixtures.webp(), "image/jpeg", ErrorCode.MEDIA_TYPE_MISMATCH);
    }

    @Test
    void rejectsOversizeDimensionsAndDecompressionPixelBounds() {
        assertInvalid(ImageTestFixtures.oversizeJpegHeader(9000, 1), "image/jpeg", ErrorCode.INVALID_IMAGE);
        assertInvalid(ImageTestFixtures.oversizePngHeader(9000, 1), "image/png", ErrorCode.INVALID_IMAGE);
        assertInvalid(ImageTestFixtures.oversizeWebpHeader(9000, 1), "image/webp", ErrorCode.INVALID_IMAGE);
        assertInvalid(ImageTestFixtures.oversizePngHeader(8000, 6000), "image/png", ErrorCode.INVALID_IMAGE);
    }

    @Test
    void rejectsHeaderOnlyPayloadsWithoutActualImageData() {
        assertInvalid(ImageTestFixtures.jpegHeaderOnly(10, 10), "image/jpeg", ErrorCode.INVALID_IMAGE);
        assertInvalid(ImageTestFixtures.pngHeaderOnly(10, 10), "image/png", ErrorCode.INVALID_IMAGE);
        assertInvalid(ImageTestFixtures.webpHeaderOnly(10, 10), "image/webp", ErrorCode.INVALID_IMAGE);
    }

    @Test
    void rejectsPayloadCorruptionThatPassesStructuralHeaderParsing() {
        assertInvalid(ImageTestFixtures.corruptPngPayload(), "image/png", ErrorCode.INVALID_IMAGE);
        assertInvalid(ImageTestFixtures.corruptJpegScan(), "image/jpeg", ErrorCode.INVALID_IMAGE);
        assertInvalid(ImageTestFixtures.corruptWebpPayload(), "image/webp", ErrorCode.INVALID_IMAGE);
    }

    @Test
    void rejectsEmptyUnsupportedAndOverByteLimitInputs() {
        assertInvalid(new byte[0], "image/png", ErrorCode.INVALID_IMAGE);
        assertInvalid(ImageTestFixtures.png(1, 1), "text/plain", ErrorCode.MEDIA_TYPE_UNSUPPORTED);
        byte[] png = ImageTestFixtures.png(1, 1);
        assertThatThrownBy(() -> validator.validate(png, "image/png", png.length - 1L))
                .isInstanceOf(AuthException.class)
                .extracting(exception -> ((AuthException) exception).getErrorCode())
                .isEqualTo(ErrorCode.MEDIA_TOO_LARGE);
    }

    private void assertImage(byte[] content, String type, long width, long height) {
        var image = validator.validate(content, type, content.length);
        assertThat(image.contentType()).isEqualTo(type);
        assertThat(image.width()).isEqualTo(width);
        assertThat(image.height()).isEqualTo(height);
    }

    private void assertInvalid(byte[] content, String type, ErrorCode expected) {
        assertThatThrownBy(() -> validator.validate(content, type, 20L * 1024 * 1024))
                .isInstanceOf(AuthException.class)
                .extracting(exception -> ((AuthException) exception).getErrorCode())
                .isEqualTo(expected);
    }

    private byte[] truncate(byte[] content) {
        return Arrays.copyOf(content, content.length - 1);
    }
}
