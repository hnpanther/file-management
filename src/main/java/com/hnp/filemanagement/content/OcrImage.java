package com.hnp.filemanagement.content;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Iterator;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * A JPEG decoded here and sent to Tesseract as a grey PNG (2026-10-14). Leptonica, which reads the image
 * for Tesseract, refuses a JPEG that a browser, a viewer and Java show without a word - "Corrupt JPEG
 * data: 78680 extraneous bytes before marker 0xda", a newspaper clipping uploaded in production, Tika's
 * 422 - while the same image decoded by Java and written again is read in three seconds. Grey because
 * recognition binarises the page anyway, and a grey raster is a third of a colour one.
 *
 * <p>Bounded: an image of more than {@link #MOST_PIXELS} is sent as it came (its raster would not fit
 * comfortably beside the application), as is one Java cannot decode (a CMYK JPEG) - Tika decides, as before.
 */
final class OcrImage {

    /** About a 600-dpi A4 page: a raster of some 30 MB grey, 90 MB while it is decoded in colour. */
    static final long MOST_PIXELS = 36_000_000L;

    private OcrImage() {
    }

    /** Whether an image of this kind is decoded here before OCR. */
    static boolean decodedHere(String contentType, String extension) {
        String type = contentType == null ? "" : contentType.toLowerCase(java.util.Locale.ROOT);
        String ext = extension == null ? "" : extension.toLowerCase(java.util.Locale.ROOT);
        return type.equals("image/jpeg") || type.equals("image/jpg") || ext.equals("jpg") || ext.equals("jpeg");
    }

    /** The image as a grey PNG, or empty when it is to be sent as it came. */
    static Optional<byte[]> asGreyPng(Supplier<InputStream> bytes) throws IOException {
        BufferedImage decoded;
        try (InputStream in = bytes.get(); ImageInputStream stream = ImageIO.createImageInputStream(in)) {
            if (stream == null) {
                return Optional.empty();
            }
            Iterator<ImageReader> readers = ImageIO.getImageReaders(stream);
            if (!readers.hasNext()) {
                return Optional.empty();
            }
            ImageReader reader = readers.next();
            try {
                reader.setInput(stream, true, true);
                if ((long) reader.getWidth(0) * reader.getHeight(0) > MOST_PIXELS) {
                    return Optional.empty();
                }
                decoded = reader.read(0);
            } catch (IOException | RuntimeException notDecoded) {
                // CMYK, a truncated file, a decoder's own limits: Tika is given the bytes as they came.
                return Optional.empty();
            } finally {
                reader.dispose();
            }
        }
        BufferedImage grey = new BufferedImage(decoded.getWidth(), decoded.getHeight(), BufferedImage.TYPE_BYTE_GRAY);
        Graphics2D g = grey.createGraphics();
        try {
            g.drawImage(decoded, 0, 0, java.awt.Color.WHITE, null);
        } finally {
            g.dispose();
        }
        ByteArrayOutputStream png = new ByteArrayOutputStream();
        if (!ImageIO.write(grey, "png", png)) {
            return Optional.empty();
        }
        return Optional.of(png.toByteArray());
    }
}
