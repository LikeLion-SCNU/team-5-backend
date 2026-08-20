package org.example.naeilbank.domain.media;

import org.example.naeilbank.global.exception.AuthException;
import org.example.naeilbank.global.exception.ErrorCode;
import org.springframework.stereotype.Component;

import javax.imageio.ImageIO;
import javax.imageio.ImageReadParam;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Iterator;
import java.util.concurrent.atomic.AtomicBoolean;

@Component
public class BoundedImageDecoder {
    static final long MAX_DECODED_PIXELS = 4_000_000;

    public void verify(byte[] content, long parsedWidth, long parsedHeight) {
        try (ImageInputStream input = ImageIO.createImageInputStream(new ByteArrayInputStream(content))) {
            if (input == null) {
                throw invalidImage();
            }
            Iterator<ImageReader> readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) {
                throw invalidImage();
            }
            ImageReader reader = readers.next();
            try {
                AtomicBoolean decoderWarning = new AtomicBoolean(false);
                reader.addIIOReadWarningListener((source, warning) -> decoderWarning.set(true));
                reader.setInput(input, true, true);
                int width = reader.getWidth(0);
                int height = reader.getHeight(0);
                if (width != parsedWidth || height != parsedHeight) {
                    throw invalidImage();
                }
                int subsampling = subsampling(width, height);
                ImageReadParam parameters = reader.getDefaultReadParam();
                parameters.setSourceSubsampling(subsampling, subsampling, 0, 0);
                BufferedImage decoded = reader.read(0, parameters);
                if (decoded == null
                        || decoderWarning.get()
                        || (long) decoded.getWidth() * decoded.getHeight() > MAX_DECODED_PIXELS
                        || decoded.getWidth() != ceilingDivide(width, subsampling)
                        || decoded.getHeight() != ceilingDivide(height, subsampling)) {
                    throw invalidImage();
                }
            } finally {
                reader.dispose();
            }
        } catch (IOException | RuntimeException e) {
            throw invalidImage();
        }
    }

    private int subsampling(int width, int height) {
        int factor = 1;
        while ((long) ceilingDivide(width, factor) * ceilingDivide(height, factor)
                > MAX_DECODED_PIXELS) {
            factor++;
        }
        return factor;
    }

    private int ceilingDivide(int value, int divisor) {
        return (value + divisor - 1) / divisor;
    }

    private AuthException invalidImage() {
        return new AuthException(ErrorCode.INVALID_IMAGE);
    }
}
