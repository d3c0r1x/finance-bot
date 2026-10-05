package com.decorix.finance.core.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;

class ReceiptImageValidatorTest {
    @Test
    void detectsDecodedPngWithoutTrustingClientMime() throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(3, 2, BufferedImage.TYPE_INT_RGB), "png", output);

        var image = ReceiptImageValidator.validate(output.toByteArray());

        assertEquals("image/png", image.mimeType());
        assertEquals("png", image.extension());
        assertEquals(3, image.width());
        assertEquals(2, image.height());
    }

    @Test
    void rejectsPixelBombFromHeaderBeforeDecodingPixels() {
        byte[] pngHeader = new byte[] {
                (byte) 0x89, 'P', 'N', 'G', 13, 10, 26, 10,
                0, 0, 0, 13, 'I', 'H', 'D', 'R',
                0, 0, 0x13, (byte) 0x89, 0, 0, 0x13, (byte) 0x88
        };

        assertThrows(IllegalArgumentException.class, () -> ReceiptImageValidator.validate(pngHeader));
    }

    @Test
    void rejectsEmptyAndMalformedImages() {
        assertThrows(IllegalArgumentException.class, () -> ReceiptImageValidator.validate(new byte[0]));
        assertThrows(IllegalArgumentException.class,
                () -> ReceiptImageValidator.validate("not an image".getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    }
}
