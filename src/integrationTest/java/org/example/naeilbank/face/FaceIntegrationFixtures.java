package org.example.naeilbank.face;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;

final class FaceIntegrationFixtures {
    private FaceIntegrationFixtures() {
    }

    static byte[] png(int width, int height) {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            if (!ImageIO.write(image, "png", output)) {
                throw new IllegalStateException("PNG writer is unavailable");
            }
            return output.toByteArray();
        } catch (java.io.IOException e) {
            throw new IllegalStateException("Could not create image fixture", e);
        }
    }
}
