package com.kirana.parchi;

import java.util.Optional;

/** The photo formats we accept, recognised by their magic bytes (never by file name or client header). */
public enum ImageType {
    JPEG("image/jpeg", "jpg"),
    PNG("image/png", "png"),
    WEBP("image/webp", "webp");

    private static final byte[] PNG_SIGNATURE = {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n'};

    private final String mimeType;
    private final String extension;

    ImageType(String mimeType, String extension) {
        this.mimeType = mimeType;
        this.extension = extension;
    }

    public String mimeType() {
        return mimeType;
    }

    public String extension() {
        return extension;
    }

    /**
     * JPEG: FF D8 FF. PNG: 89 'PNG' CR LF 1A LF. WebP: "RIFF" + 4 size bytes + "WEBP".
     * Anything else (including text renamed to .jpg) is empty.
     */
    public static Optional<ImageType> detect(byte[] b) {
        if (b == null) {
            return Optional.empty();
        }
        if (b.length >= 3 && (b[0] & 0xFF) == 0xFF && (b[1] & 0xFF) == 0xD8 && (b[2] & 0xFF) == 0xFF) {
            return Optional.of(JPEG);
        }
        if (startsWith(b, PNG_SIGNATURE, 0)) {
            return Optional.of(PNG);
        }
        if (b.length >= 12 && startsWith(b, new byte[] {'R', 'I', 'F', 'F'}, 0)
                && startsWith(b, new byte[] {'W', 'E', 'B', 'P'}, 8)) {
            return Optional.of(WEBP);
        }
        return Optional.empty();
    }

    private static boolean startsWith(byte[] b, byte[] prefix, int offset) {
        if (b.length < offset + prefix.length) {
            return false;
        }
        for (int i = 0; i < prefix.length; i++) {
            if (b[offset + i] != prefix[i]) {
                return false;
            }
        }
        return true;
    }
}
