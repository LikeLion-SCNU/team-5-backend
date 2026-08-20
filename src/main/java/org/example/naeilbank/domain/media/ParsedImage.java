package org.example.naeilbank.domain.media;

record ParsedImage(ImageFormat format, long width, long height) {
}

enum ImageFormat {
    JPEG("image/jpeg"),
    PNG("image/png"),
    WEBP("image/webp");

    private final String contentType;

    ImageFormat(String contentType) {
        this.contentType = contentType;
    }

    String contentType() {
        return contentType;
    }
}
