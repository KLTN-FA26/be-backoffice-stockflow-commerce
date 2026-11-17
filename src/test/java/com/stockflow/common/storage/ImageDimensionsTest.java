package com.stockflow.common.storage;

import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link ImageDimensions} measures the upright size from the header: a quarter-turn EXIF orientation swaps width and
 * height, nothing is decoded, and an oversized or unreadable image is refused.
 */
class ImageDimensionsTest {

    @TempDir
    Path dir;

    @Test
    void measuresAPngWithoutMetadata() throws Exception {
        byte[] png = encode("png", 300, 200);

        assertThat(ImageDimensions.of(png)).isEqualTo(new ImageDimensions.Size(300, 200));
        Path file = Files.write(dir.resolve("a.png"), png);
        assertThat(ImageDimensions.of(file)).isEqualTo(new ImageDimensions.Size(300, 200));
    }

    @ParameterizedTest
    @CsvSource({"1, 300, 200", "3, 300, 200", "5, 200, 300", "6, 200, 300", "8, 200, 300", "9, 300, 200"})
    void aQuarterTurnOrientationSwapsWidthAndHeight(int orientation, int width, int height) throws Exception {
        byte[] jpeg = withOrientation(encode("jpeg", 300, 200), orientation);

        assertThat(ImageDimensions.of(jpeg)).isEqualTo(new ImageDimensions.Size(width, height));
        Path file = Files.write(dir.resolve("a.jpg"), jpeg);
        assertThat(ImageDimensions.of(file)).isEqualTo(new ImageDimensions.Size(width, height));
    }

    @Test
    void bytesNoReaderUnderstandsAreRefused() {
        assertThatThrownBy(() -> ImageDimensions.of("not an image".getBytes()))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).errorCode())
                .isEqualTo(ErrorCode.VALIDATION_FAILED);
    }

    @Test
    void anImageOverFortyMegapixelsIsRefusedUnread() throws Exception {
        // A PNG header claiming 8000 x 5001 pixels: refused on the header alone, nothing is decoded.
        byte[] png = encode("png", 1, 1);
        java.nio.ByteBuffer.wrap(png, 16, 8).putInt(8000).putInt(5001);

        assertThatThrownBy(() -> ImageDimensions.of(png))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).errorCode())
                .isEqualTo(ErrorCode.PAYLOAD_TOO_LARGE);
    }

    private static byte[] encode(String format, int width, int height) throws Exception {
        var image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        var out = new ByteArrayOutputStream();
        ImageIO.write(image, format, out);
        return out.toByteArray();
    }

    /** {@code jpeg} with an APP1 Exif segment carrying only an IFD0 Orientation tag, right after the SOI marker. */
    private static byte[] withOrientation(byte[] jpeg, int orientation) {
        byte[] app1 = {
                (byte) 0xFF, (byte) 0xE1, 0x00, 0x22,
                'E', 'x', 'i', 'f', 0x00, 0x00,
                'I', 'I', 0x2A, 0x00, 0x08, 0x00, 0x00, 0x00,
                0x01, 0x00,
                0x12, 0x01, 0x03, 0x00, 0x01, 0x00, 0x00, 0x00, (byte) orientation, 0x00, 0x00, 0x00,
                0x00, 0x00, 0x00, 0x00
        };
        byte[] out = new byte[jpeg.length + app1.length];
        System.arraycopy(jpeg, 0, out, 0, 2);
        System.arraycopy(app1, 0, out, 2, app1.length);
        System.arraycopy(jpeg, 2, out, 2 + app1.length, jpeg.length - 2);
        return out;
    }

    /** Review of 2F-4: whatever the metadata reader throws on a crafted block is a bad image (400), not a 500. */
    @Test
    void metadataTheReaderTripsOverIsAValidationFailure() {
        var hostile = new java.io.InputStream() {
            @Override
            public int read() {
                throw new IllegalStateException("reader bug");
            }
        };

        assertThatThrownBy(() -> ImageDimensions.orientation(hostile))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).errorCode())
                .isEqualTo(ErrorCode.VALIDATION_FAILED);
    }
}
