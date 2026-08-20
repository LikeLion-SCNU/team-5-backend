package org.example.naeilbank.domain.media;

import org.example.naeilbank.global.exception.AuthException;
import org.example.naeilbank.global.exception.ErrorCode;

final class WebpHeaderParser {
    private static final int ANIMATION_FLAG = 0x02;

    private WebpHeaderParser() {
    }

    static ParsedImage parse(byte[] content) {
        long containerEnd = unsignedInt(content, 4) + 8;
        if (containerEnd < 20 || containerEnd > content.length) {
            throw invalidImage();
        }
        int position = 12;
        long canvasWidth = -1;
        long canvasHeight = -1;
        while ((long) position + 8 <= containerEnd) {
            long chunkSize = unsignedInt(content, position + 4);
            long dataStart = position + 8L;
            long chunkEnd = dataStart + chunkSize + (chunkSize & 1L);
            if (chunkEnd > containerEnd || chunkEnd > content.length) {
                throw invalidImage();
            }
            if (ascii(content, position, "VP8X")) {
                if (chunkSize != 10 || (unsigned(content[Math.toIntExact(dataStart)]) & ANIMATION_FLAG) != 0) {
                    throw invalidImage();
                }
                canvasWidth = little24(content, Math.toIntExact(dataStart + 4)) + 1;
                canvasHeight = little24(content, Math.toIntExact(dataStart + 7)) + 1;
            } else if (ascii(content, position, "VP8 ")) {
                ParsedImage image = parseLossy(content, dataStart, chunkSize);
                return requireCanvasMatch(image, canvasWidth, canvasHeight);
            } else if (ascii(content, position, "VP8L")) {
                ParsedImage image = parseLossless(content, dataStart, chunkSize);
                return requireCanvasMatch(image, canvasWidth, canvasHeight);
            }
            position = Math.toIntExact(chunkEnd);
        }
        throw invalidImage();
    }

    private static ParsedImage parseLossy(byte[] content, long dataStart, long chunkSize) {
        if (chunkSize < 10) {
            throw invalidImage();
        }
        int data = Math.toIntExact(dataStart);
        if (unsigned(content[data + 3]) != 0x9d
                || unsigned(content[data + 4]) != 0x01
                || unsigned(content[data + 5]) != 0x2a) {
            throw invalidImage();
        }
        long width = unsignedShort(content, data + 6) & 0x3fff;
        long height = unsignedShort(content, data + 8) & 0x3fff;
        return new ParsedImage(ImageFormat.WEBP, width, height);
    }

    private static ParsedImage parseLossless(byte[] content, long dataStart, long chunkSize) {
        if (chunkSize < 5) {
            throw invalidImage();
        }
        int data = Math.toIntExact(dataStart);
        if (unsigned(content[data]) != 0x2f) {
            throw invalidImage();
        }
        long dimensions = unsignedInt(content, data + 1);
        long width = (dimensions & 0x3fff) + 1;
        long height = ((dimensions >>> 14) & 0x3fff) + 1;
        return new ParsedImage(ImageFormat.WEBP, width, height);
    }

    private static ParsedImage requireCanvasMatch(ParsedImage image, long width, long height) {
        if (width > 0 && (image.width() != width || image.height() != height)) {
            throw invalidImage();
        }
        return image;
    }

    private static boolean ascii(byte[] content, int offset, String expected) {
        if (offset < 0 || offset + expected.length() > content.length) {
            return false;
        }
        for (int index = 0; index < expected.length(); index++) {
            if (unsigned(content[offset + index]) != expected.charAt(index)) {
                return false;
            }
        }
        return true;
    }

    private static int unsigned(byte value) {
        return value & 0xff;
    }

    private static int unsignedShort(byte[] content, int offset) {
        return unsigned(content[offset]) | unsigned(content[offset + 1]) << 8;
    }

    private static long unsignedInt(byte[] content, int offset) {
        return Integer.toUnsignedLong(
                unsigned(content[offset])
                        | unsigned(content[offset + 1]) << 8
                        | unsigned(content[offset + 2]) << 16
                        | unsigned(content[offset + 3]) << 24
        );
    }

    private static long little24(byte[] content, int offset) {
        return unsigned(content[offset])
                | (long) unsigned(content[offset + 1]) << 8
                | (long) unsigned(content[offset + 2]) << 16;
    }

    private static AuthException invalidImage() {
        return new AuthException(ErrorCode.INVALID_IMAGE);
    }
}
