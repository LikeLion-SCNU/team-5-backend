package org.example.naeilbank.media;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Base64;

final class MediaIntegrationFixtures {
    private static final String ONE_PIXEL_WEBP =
            "UklGRhoAAABXRUJQVlA4TA0AAAAvAAAAEAcQERGIiP4HAA==";

    private MediaIntegrationFixtures() {
    }

    static byte[] png(int width, int height) {
        return encode("png", width, height);
    }

    static byte[] jpeg(int width, int height) {
        return encode("jpeg", width, height);
    }

    static byte[] webp() {
        return Base64.getDecoder().decode(ONE_PIXEL_WEBP);
    }

    private static byte[] encode(String format, int width, int height) {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        image.setRGB(0, 0, 0x336699);
        try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            if (!ImageIO.write(image, format, output)) {
                throw new IllegalStateException("Image writer is unavailable: " + format);
            }
            return output.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException("Could not create image fixture", e);
        }
    }
}
