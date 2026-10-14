package com.hnp.filemanagement.content;

import com.hnp.filemanagement.support.TestTika;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.time.Duration;
import java.util.Arrays;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A JPEG Tesseract's decoder refuses and Java reads (2026-10-14, a newspaper clipping in production):
 * extra bytes before the scan's start, as the clipping had. Sent as it is, Tika answers 422; decoded
 * here and sent as a grey PNG, its words are read. Against the real Tika image ({@link TestTika}).
 */
class OcrImageTest {

    @Test
    @DisplayName("a JPEG with bytes before its scan: Tika refuses it as it came, and reads it decoded here")
    void aJpegTesseractRefuses() throws Exception {
        byte[] corrupt = corrupt(jpegOfThePhoto());
        BufferedImage asJavaSees = ImageIO.read(new ByteArrayInputStream(corrupt));
        assertThat(asJavaSees).as("Java decodes it").isNotNull();

        HttpTikaClient tika = new HttpTikaClient(URI.create(TestTika.url()), URI.create(TestTika.url()), Duration.ofSeconds(5),
                Duration.ofMinutes(6), Duration.ofMinutes(10));
        assertThatThrownBy(() -> tika.read(TikaClient.Lane.OCR, null, () -> new ByteArrayInputStream(corrupt), corrupt.length,
                "image/jpeg", "jpg", 1_000_000))
                .as("Tesseract's decoder refuses it").isInstanceOf(TikaClient.Refused.class);

        ContentReader reader = new ContentReader(tika, new ContentSearchProperties.Extraction(true, false, true, 1, 200, 10, 3, 10,
                500, 30, 40));
        ContentReader.Result result = reader.read(new FileContentRepository.Target(1, "files/x.jpg", "image/jpeg", "jpg",
                corrupt.length, 0), () -> new ByteArrayInputStream(corrupt));
        assertThat(result.outcome().state()).isEqualTo("DONE");
        assertThat(result.outcome().detectedType()).as("what the revision is, not the PNG it was sent as").isEqualTo("image/jpeg");
        assertThat(result.pages()).extracting(FileContentRepository.PageRow::text).anySatisfy(text ->
                assertThat(text).contains("انبار"));
    }

    @Test
    @DisplayName("decoded here: a JPEG becomes a grey PNG of its size; what Java cannot read, or too large, is sent as it came")
    void bounds() throws Exception {
        byte[] jpeg = jpegOfThePhoto();
        BufferedImage original = ImageIO.read(new ByteArrayInputStream(jpeg));
        Optional<byte[]> png = OcrImage.asGreyPng(() -> new ByteArrayInputStream(jpeg));
        assertThat(png).isPresent();
        BufferedImage grey = ImageIO.read(new ByteArrayInputStream(png.get()));
        assertThat(grey.getWidth()).isEqualTo(original.getWidth());
        assertThat(grey.getHeight()).isEqualTo(original.getHeight());
        assertThat(grey.getColorModel().getNumComponents()).isEqualTo(1);

        assertThat(OcrImage.asGreyPng(() -> new ByteArrayInputStream("not an image".getBytes()))).isEmpty();
        byte[] truncated = Arrays.copyOf(jpeg, 200);
        assertThat(OcrImage.asGreyPng(() -> new ByteArrayInputStream(truncated))).isEmpty();
        assertThat(OcrImage.decodedHere("image/jpeg", "jpg")).isTrue();
        assertThat(OcrImage.decodedHere("application/octet-stream", "JPEG")).isTrue();
        assertThat(OcrImage.decodedHere("image/png", "png")).isFalse();
        assertThat(OcrImage.MOST_PIXELS).as("a 600-dpi A4 page, not more").isBetween(30_000_000L, 40_000_000L);
    }

    /** The corpus's photo as a JPEG - colour, as a camera writes one. */
    private static byte[] jpegOfThePhoto() throws IOException {
        BufferedImage photo;
        try (InputStream in = OcrImageTest.class.getResourceAsStream("/content/photo.png")) {
            photo = ImageIO.read(in);
        }
        BufferedImage rgb = new BufferedImage(photo.getWidth(), photo.getHeight(), BufferedImage.TYPE_INT_RGB);
        rgb.createGraphics().drawImage(photo, 0, 0, java.awt.Color.WHITE, null);
        ByteArrayOutputStream jpeg = new ByteArrayOutputStream();
        assertThat(ImageIO.write(rgb, "jpg", jpeg)).isTrue();
        return jpeg.toByteArray();
    }

    /** The JPEG with 20,000 bytes of nothing before its scan's start marker (FF DA) - what the clipping had. */
    private static byte[] corrupt(byte[] jpeg) {
        int sos = -1;
        for (int i = 2; i < jpeg.length - 1; i++) {
            if ((jpeg[i] & 0xff) == 0xFF && (jpeg[i + 1] & 0xff) == 0xDA) {
                sos = i;
                break;
            }
        }
        assertThat(sos).isPositive();
        byte[] junk = new byte[20_000];
        Arrays.fill(junk, (byte) 0x20);
        byte[] out = new byte[jpeg.length + junk.length];
        System.arraycopy(jpeg, 0, out, 0, sos);
        System.arraycopy(junk, 0, out, sos, junk.length);
        System.arraycopy(jpeg, sos, out, sos + junk.length, jpeg.length - sos);
        return out;
    }
}
