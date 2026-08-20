package org.example.naeilbank.domain.media;

import org.example.naeilbank.global.exception.AuthException;
import org.example.naeilbank.global.exception.ErrorCode;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

final class ImageHeaderParser {
    private static final byte[] PNG_SIGNATURE = {
            (byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a
    };

    private ImageHeaderParser() {
    }

    static ParsedImage parse(byte[] content) {
        ImageFormat format = detect(content);
        return switch (format) {
            case JPEG -> parseJpeg(content);
            case PNG -> parsePng(content);
            case WEBP -> WebpHeaderParser.parse(content);
        };
    }

    private static ImageFormat detect(byte[] content) {
        if (content.length >= PNG_SIGNATURE.length
                && Arrays.equals(PNG_SIGNATURE, Arrays.copyOf(content, PNG_SIGNATURE.length))) {
            return ImageFormat.PNG;
        }
        if (content.length >= 2 && unsigned(content[0]) == 0xff && unsigned(content[1]) == 0xd8) {
            return ImageFormat.JPEG;
        }
        if (content.length >= 12 && ascii(content, 0, "RIFF") && ascii(content, 8, "WEBP")) {
            return ImageFormat.WEBP;
        }
        throw invalidImage();
    }

    private static ParsedImage parsePng(byte[] content) {
        if (content.length < 45) {
            throw invalidImage();
        }
        int position = 8;
        long width = -1;
        long height = -1;
        boolean firstChunk = true;
        boolean hasImageData = false;
        while (position + 12 <= content.length) {
            long chunkLength = unsignedIntBigEndian(content, position);
            if (chunkLength > Integer.MAX_VALUE) {
                throw invalidImage();
            }
            long chunkEnd = (long) position + 12 + chunkLength;
            if (chunkEnd > content.length) {
                throw invalidImage();
            }
            String chunkType = new String(content, position + 4, 4, StandardCharsets.US_ASCII);
            if (firstChunk) {
                if (!"IHDR".equals(chunkType) || chunkLength != 13) {
                    throw invalidImage();
                }
                width = unsignedIntBigEndian(content, position + 8);
                height = unsignedIntBigEndian(content, position + 12);
                firstChunk = false;
            }
            if ("IDAT".equals(chunkType)) {
                hasImageData = true;
            }
            if ("IEND".equals(chunkType)) {
                if (chunkLength != 0 || !hasImageData || width < 1 || height < 1) {
                    throw invalidImage();
                }
                return new ParsedImage(ImageFormat.PNG, width, height);
            }
            position = Math.toIntExact(chunkEnd);
        }
        throw invalidImage();
    }

    private static ParsedImage parseJpeg(byte[] content) {
        if (!hasTerminalJpegEnd(content)) {
            throw invalidImage();
        }
        int position = 2;
        long width = -1;
        long height = -1;
        while (position < content.length) {
            if (unsigned(content[position]) != 0xff) {
                throw invalidImage();
            }
            while (position < content.length && unsigned(content[position]) == 0xff) {
                position++;
            }
            if (position >= content.length) {
                throw invalidImage();
            }
            int marker = unsigned(content[position++]);
            if (marker == 0xd9) {
                break;
            }
            if (marker == 0x01 || marker >= 0xd0 && marker <= 0xd8) {
                continue;
            }
            if (position + 2 > content.length) {
                throw invalidImage();
            }
            int segmentLength = unsignedShort(content, position);
            if (segmentLength < 2 || (long) position + segmentLength > content.length) {
                throw invalidImage();
            }
            if (isStartOfFrame(marker)) {
                if (segmentLength < 11) {
                    throw invalidImage();
                }
                height = unsignedShort(content, position + 3);
                width = unsignedShort(content, position + 5);
                int componentCount = unsigned(content[position + 7]);
                if (componentCount < 1 || componentCount > 4
                        || segmentLength != 8 + 3 * componentCount) {
                    throw invalidImage();
                }
            }
            if (marker == 0xda) {
                if (width < 1 || height < 1 || position + segmentLength >= content.length - 2) {
                    throw invalidImage();
                }
                return new ParsedImage(ImageFormat.JPEG, width, height);
            }
            position += segmentLength;
        }
        throw invalidImage();
    }

    private static boolean hasTerminalJpegEnd(byte[] content) {
        return content.length >= 4
                && unsigned(content[content.length - 2]) == 0xff
                && unsigned(content[content.length - 1]) == 0xd9;
    }

    private static boolean isStartOfFrame(int marker) {
        return marker >= 0xc0 && marker <= 0xcf
                && marker != 0xc4 && marker != 0xc8 && marker != 0xcc;
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
        return unsigned(content[offset]) << 8 | unsigned(content[offset + 1]);
    }

    private static long unsignedIntBigEndian(byte[] content, int offset) {
        return Integer.toUnsignedLong(
                unsigned(content[offset]) << 24
                        | unsigned(content[offset + 1]) << 16
                        | unsigned(content[offset + 2]) << 8
                        | unsigned(content[offset + 3])
        );
    }

    private static AuthException invalidImage() {
        return new AuthException(ErrorCode.INVALID_IMAGE);
    }

}
