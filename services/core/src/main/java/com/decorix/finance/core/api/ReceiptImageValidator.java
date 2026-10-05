package com.decorix.finance.core.api;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Iterator;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;

final class ReceiptImageValidator {
    static final int MAX_BYTES = 10 * 1024 * 1024;
    static final long MAX_PIXELS = 25_000_000L;

    private ReceiptImageValidator() {}

    static DetectedImage validate(byte[] bytes) {
        if (bytes == null || bytes.length == 0 || bytes.length > MAX_BYTES) {
            throw new IllegalArgumentException("Receipt image size is invalid");
        }
        try (ImageInputStream input = ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
            if (input == null) throw invalidImage();
            Iterator<ImageReader> readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) throw invalidImage();
            ImageReader reader = readers.next();
            try {
                reader.setInput(input, true, true);
                String format = reader.getFormatName().toLowerCase(java.util.Locale.ROOT);
                if (!format.equals("png") && !format.equals("jpeg") && !format.equals("jpg")) {
                    throw invalidImage();
                }
                int width = reader.getWidth(0);
                int height = reader.getHeight(0);
                if (width <= 0 || height <= 0 || (long) width * height > MAX_PIXELS) throw invalidImage();
                BufferedImage decoded = reader.read(0);
                if (decoded == null || decoded.getWidth() != width || decoded.getHeight() != height) throw invalidImage();
                String mimeType = format.equals("png") ? "image/png" : "image/jpeg";
                if (!hasSignature(bytes, mimeType)) throw invalidImage();
                return new DetectedImage(mimeType, mimeType.equals("image/png") ? "png" : "jpg", width, height);
            } finally {
                reader.dispose();
            }
        } catch (IOException | RuntimeException error) {
            if (error instanceof IllegalArgumentException illegalArgument) throw illegalArgument;
            throw invalidImage();
        }
    }

    private static boolean hasSignature(byte[] bytes, String mimeType) {
        if (mimeType.equals("image/png")) {
            byte[] signature = {(byte) 0x89, 'P', 'N', 'G', 13, 10, 26, 10};
            if (bytes.length < signature.length) return false;
            for (int i = 0; i < signature.length; i++) if (bytes[i] != signature[i]) return false;
            return true;
        }
        return bytes.length >= 3 && (bytes[0] & 0xff) == 0xff && (bytes[1] & 0xff) == 0xd8
                && (bytes[2] & 0xff) == 0xff;
    }

    private static IllegalArgumentException invalidImage() {
        return new IllegalArgumentException("Receipt image is invalid or unsupported");
    }

    record DetectedImage(String mimeType, String extension, int width, int height) {}
}
