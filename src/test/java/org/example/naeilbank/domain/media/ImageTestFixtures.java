package org.example.naeilbank.domain.media;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;

final class ImageTestFixtures {
    private static final String ONE_PIXEL_WEBP =
            "UklGRhoAAABXRUJQVlA4TA0AAAAvAAAAEAcQERGIiP4HAA==";

    private ImageTestFixtures() {
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

    static byte[] pngHeaderOnly(int width, int height) {
        byte[] image = pngHeader(width, height, false);
        putAscii(image, 37, "IEND");
        return image;
    }

    static byte[] jpegHeaderOnly(int width, int height) {
        byte[] image = jpegHeader(width, height, 17);
        image[15] = (byte) 0xff;
        image[16] = (byte) 0xd9;
        return image;
    }

    static byte[] webpHeaderOnly(int width, int height) {
        byte[] image = new byte[30];
        putAscii(image, 0, "RIFF");
        putLittleEndian(image, 4, 22);
        putAscii(image, 8, "WEBP");
        putAscii(image, 12, "VP8X");
        putLittleEndian(image, 16, 10);
        putLittle24(image, 24, width - 1);
        putLittle24(image, 27, height - 1);
        return image;
    }

    static byte[] oversizePngHeader(int width, int height) {
        return pngHeader(width, height, true);
    }

    static byte[] oversizeJpegHeader(int width, int height) {
        byte[] image = jpegHeader(width, height, 28);
        image[15] = (byte) 0xff;
        image[16] = (byte) 0xda;
        image[18] = 8;
        image[19] = 1;
        image[20] = 1;
        image[23] = 63;
        image[26] = (byte) 0xff;
        image[27] = (byte) 0xd9;
        return image;
    }

    static byte[] oversizeWebpHeader(int width, int height) {
        byte[] image = new byte[26];
        putAscii(image, 0, "RIFF");
        putLittleEndian(image, 4, 18);
        putAscii(image, 8, "WEBP");
        putAscii(image, 12, "VP8L");
        putLittleEndian(image, 16, 5);
        image[20] = 0x2f;
        putLittleEndian(image, 21, width - 1 | (height - 1) << 14);
        return image;
    }

    static byte[] corruptPngPayload() {
        byte[] image = png(8, 8);
        int type = findAscii(image, "IDAT");
        int length = readBigEndian(image, type - 4);
        Arrays.fill(image, type + 4, type + 4 + length, (byte) 0xff);
        return image;
    }

    static byte[] corruptJpegScan() {
        byte[] image = jpeg(8, 8);
        int marker = findMarker(image, 0xda);
        int segmentLength = (image[marker + 2] & 0xff) << 8 | image[marker + 3] & 0xff;
        int scanStart = marker + 2 + segmentLength;
        byte[] corrupt = Arrays.copyOf(image, scanStart + 3);
        corrupt[scanStart] = 0;
        corrupt[scanStart + 1] = (byte) 0xff;
        corrupt[scanStart + 2] = (byte) 0xd9;
        return corrupt;
    }

    static byte[] corruptWebpPayload() {
        byte[] valid = webp();
        byte[] corrupt = Arrays.copyOf(valid, 26);
        putLittleEndian(corrupt, 4, 18);
        putLittleEndian(corrupt, 16, 5);
        corrupt[25] = 0;
        return corrupt;
    }

    private static byte[] encode(String format, int width, int height) {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        image.setRGB(0, 0, 0x336699);
        try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            if (!ImageIO.write(image, format, output)) {
                throw new IllegalStateException("Missing test image writer: " + format);
            }
            return output.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException("Could not encode test image", e);
        }
    }

    private static byte[] pngHeader(int width, int height, boolean includeImageData) {
        byte[] image = new byte[includeImageData ? 58 : 45];
        byte[] signature = {
                (byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a
        };
        System.arraycopy(signature, 0, image, 0, signature.length);
        putBigEndian(image, 8, 13);
        putAscii(image, 12, "IHDR");
        putBigEndian(image, 16, width);
        putBigEndian(image, 20, height);
        image[24] = 8;
        image[25] = 2;
        if (includeImageData) {
            putBigEndian(image, 33, 1);
            putAscii(image, 37, "IDAT");
            putAscii(image, 50, "IEND");
        }
        return image;
    }

    private static byte[] jpegHeader(int width, int height, int length) {
        byte[] image = new byte[length];
        image[0] = (byte) 0xff;
        image[1] = (byte) 0xd8;
        image[2] = (byte) 0xff;
        image[3] = (byte) 0xc0;
        image[5] = 11;
        image[6] = 8;
        putShort(image, 7, height);
        putShort(image, 9, width);
        image[11] = 1;
        image[12] = 1;
        image[13] = 0x11;
        return image;
    }

    private static int findAscii(byte[] bytes, String value) {
        byte[] target = value.getBytes(StandardCharsets.US_ASCII);
        for (int index = 0; index <= bytes.length - target.length; index++) {
            if (Arrays.equals(target, Arrays.copyOfRange(bytes, index, index + target.length))) {
                return index;
            }
        }
        throw new IllegalStateException("Chunk not found: " + value);
    }

    private static int findMarker(byte[] bytes, int marker) {
        for (int index = 0; index + 1 < bytes.length; index++) {
            if ((bytes[index] & 0xff) == 0xff && (bytes[index + 1] & 0xff) == marker) {
                return index;
            }
        }
        throw new IllegalStateException("JPEG marker not found");
    }

    private static int readBigEndian(byte[] bytes, int offset) {
        return (bytes[offset] & 0xff) << 24
                | (bytes[offset + 1] & 0xff) << 16
                | (bytes[offset + 2] & 0xff) << 8
                | bytes[offset + 3] & 0xff;
    }

    private static void putAscii(byte[] target, int offset, String value) {
        byte[] ascii = value.getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(ascii, 0, target, offset, ascii.length);
    }

    private static void putBigEndian(byte[] target, int offset, int value) {
        target[offset] = (byte) (value >>> 24);
        target[offset + 1] = (byte) (value >>> 16);
        target[offset + 2] = (byte) (value >>> 8);
        target[offset + 3] = (byte) value;
    }

    private static void putLittleEndian(byte[] target, int offset, int value) {
        target[offset] = (byte) value;
        target[offset + 1] = (byte) (value >>> 8);
        target[offset + 2] = (byte) (value >>> 16);
        target[offset + 3] = (byte) (value >>> 24);
    }

    private static void putLittle24(byte[] target, int offset, int value) {
        target[offset] = (byte) value;
        target[offset + 1] = (byte) (value >>> 8);
        target[offset + 2] = (byte) (value >>> 16);
    }

    private static void putShort(byte[] target, int offset, int value) {
        target[offset] = (byte) (value >>> 8);
        target[offset + 1] = (byte) value;
    }
}
