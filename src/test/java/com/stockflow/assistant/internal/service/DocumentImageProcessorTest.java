package com.stockflow.assistant.internal.service;

import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.storage.FileUpload;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.geom.AffineTransform;
import java.awt.geom.Point2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.zip.CRC32;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * What photo is accepted, and what is sent in its place: the type is judged by the bytes, and the copy that leaves is
 * an upright JPEG of bounded size with none of the upload's metadata.
 */
class DocumentImageProcessorTest {

    private final DocumentImageProcessor images = new DocumentImageProcessor();

    @Test
    @DisplayName("the declared type must be JPEG, PNG or WEBP and the first bytes must be that type")
    void typesAreJudgedByTheBytes() throws IOException {
        byte[] jpeg = jpeg(new BufferedImage(8, 8, BufferedImage.TYPE_INT_RGB));
        byte[] png = png(new BufferedImage(8, 8, BufferedImage.TYPE_INT_RGB));

        assertThat(images.accept(upload(jpeg, "image/jpeg"))).isEqualTo(jpeg);
        assertThat(images.accept(upload(jpeg, "image/jpeg; charset=binary"))).isEqualTo(jpeg);
        assertThat(images.accept(upload(png, "IMAGE/PNG"))).isEqualTo(png);
        assertThat(DocumentImageProcessor.sniff("RIFF\0\0\0\0WEBPVP8 ".getBytes(StandardCharsets.ISO_8859_1)))
                .isEqualTo("image/webp");

        assertThat(refusal(() -> images.accept(upload(png, "image/jpeg"))))
                .as("a PNG called a JPEG").isEqualTo(ErrorCode.UNSUPPORTED_MEDIA_TYPE);
        assertThat(refusal(() -> images.accept(upload("%PDF-1.7".getBytes(StandardCharsets.US_ASCII),
                "application/pdf")))).isEqualTo(ErrorCode.UNSUPPORTED_MEDIA_TYPE);
        assertThat(refusal(() -> images.accept(upload(jpeg, "image/heic"))))
                .isEqualTo(ErrorCode.UNSUPPORTED_MEDIA_TYPE);
        assertThat(refusal(() -> images.accept(upload(jpeg, null)))).isEqualTo(ErrorCode.UNSUPPORTED_MEDIA_TYPE);
        assertThat(refusal(() -> images.accept(upload("GIF89a....".getBytes(StandardCharsets.US_ASCII),
                "image/webp")))).isEqualTo(ErrorCode.UNSUPPORTED_MEDIA_TYPE);
    }

    @Test
    @DisplayName("above 5 MB is 413, whether the upload says so or lies about its size; an empty file is 400")
    void sizes() {
        byte[] tooMany = new byte[(int) DocumentImageProcessor.MAX_BYTES + 1];
        tooMany[0] = (byte) 0xFF;
        tooMany[1] = (byte) 0xD8;
        tooMany[2] = (byte) 0xFF;

        assertThat(refusal(() -> images.accept(new FileUpload("note.jpg", "image/jpeg", tooMany.length,
                unreadable())))).as("refused on the declared size, before a byte is read")
                .isEqualTo(ErrorCode.PAYLOAD_TOO_LARGE);
        assertThat(refusal(() -> images.accept(new FileUpload("note.jpg", "image/jpeg", 10,
                new ByteArrayInputStream(tooMany))))).isEqualTo(ErrorCode.PAYLOAD_TOO_LARGE);
        assertThat(refusal(() -> images.accept(upload(new byte[0], "image/jpeg"))))
                .isEqualTo(ErrorCode.VALIDATION_FAILED);
    }

    @Test
    @DisplayName("a phone photo stored sideways is sent upright, as a JPEG with no EXIF left in it")
    void orientationAndMetadata() throws IOException {
        // Landscape as stored, with a dark block in the stored top-left corner, tagged "turn 90 degrees clockwise".
        BufferedImage stored = new BufferedImage(80, 40, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = stored.createGraphics();
        graphics.setColor(Color.WHITE);
        graphics.fillRect(0, 0, 80, 40);
        graphics.setColor(Color.BLACK);
        graphics.fillRect(0, 0, 16, 16);
        graphics.dispose();
        byte[] photo = withOrientation(jpeg(stored), 6);
        assertThat(DocumentImageProcessor.orientation(photo)).isEqualTo(6);
        assertThat(contains(photo, "Exif")).isTrue();

        DocumentImageProcessor.Prepared sent = images.prepare(photo);

        assertThat(sent.image().mediaType()).isEqualTo("image/jpeg");
        assertThat(List.of(sent.width(), sent.height())).containsExactly(40, 80);
        BufferedImage upright = ImageIO.read(new ByteArrayInputStream(sent.image().bytes()));
        assertThat(upright.getWidth()).isEqualTo(40);
        // Turned clockwise, the stored top-left corner is the top-right one.
        assertThat(luminance(upright.getRGB(32, 8))).isLessThan(80);
        assertThat(luminance(upright.getRGB(8, 8))).isGreaterThan(200);
        assertThat(contains(sent.image().bytes(), "Exif")).as("no metadata goes out").isFalse();
        assertThat(DocumentImageProcessor.orientation(sent.image().bytes())).isEqualTo(1);
    }

    @Test
    @DisplayName("the long edge sent is at most 2576 pixels, and a transparent image is drawn on white")
    void sizeAndBackground() throws IOException {
        DocumentImageProcessor.Prepared wide = images.prepare(png(new BufferedImage(3000, 1000,
                BufferedImage.TYPE_INT_RGB)));
        assertThat(List.of(wide.width(), wide.height())).containsExactly(DocumentImageProcessor.MAX_EDGE, 859);

        DocumentImageProcessor.Prepared clear = images.prepare(png(new BufferedImage(20, 20,
                BufferedImage.TYPE_INT_ARGB)));
        BufferedImage sent = ImageIO.read(new ByteArrayInputStream(clear.image().bytes()));
        assertThat(luminance(sent.getRGB(10, 10))).as("transparent reads as paper, not as black").isGreaterThan(240);
        assertThat(List.of(clear.width(), clear.height())).as("never enlarged").containsExactly(20, 20);
    }

    @Test
    @DisplayName("a photo too detailed for 3.5 MB at the best quality is sent at a lower one, never above the bound")
    void aDetailedPhotoIsSentSmaller() throws IOException {
        // Noise is the worst a JPEG can be given: nothing in it compresses.
        Random random = new Random(7);
        BufferedImage noise = new BufferedImage(DocumentImageProcessor.MAX_EDGE, 1932, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < noise.getHeight(); y++) {
            for (int x = 0; x < noise.getWidth(); x++) {
                noise.setRGB(x, y, random.nextInt(0x1000000));
            }
        }
        assertThat(DocumentImageProcessor.jpeg(noise, DocumentImageProcessor.JPEG_QUALITIES[0]).length)
                .as("the best quality alone would be too much").isGreaterThan(DocumentImageProcessor.MAX_SENT_BYTES);

        DocumentImageProcessor.Prepared sent = DocumentImageProcessor.render(noise, 1);

        assertThat(sent.image().bytes().length).isLessThanOrEqualTo(DocumentImageProcessor.MAX_SENT_BYTES);
        assertThat(List.of(sent.width(), sent.height())).containsExactly(DocumentImageProcessor.MAX_EDGE, 1932);
    }

    @Test
    @DisplayName("a file that does not decode is the user's 400; an image above 50 megapixels is refused unread")
    void unreadableAndHuge() {
        byte[] damaged = Arrays.copyOf(new byte[] {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 0, 16}, 64);
        assertThat(refusal(() -> images.prepare(damaged))).isEqualTo(ErrorCode.VALIDATION_FAILED);

        assertThat(refusal(() -> images.prepare(pngHeader(10_000, 10_000)))).isEqualTo(ErrorCode.PAYLOAD_TOO_LARGE);
    }

    @Test
    @DisplayName("subsampling keeps the decode near its budget without going below the edge that is sent")
    void subsampling() {
        assertThat(DocumentImageProcessor.subsamplingStep(4000, 3000)).as("a 12 MP phone photo").isEqualTo(1);
        assertThat(DocumentImageProcessor.subsamplingStep(8000, 6000)).as("48 MP").isEqualTo(2);
        assertThat(DocumentImageProcessor.subsamplingStep(2000, 1000)).isEqualTo(1);
        assertThat(DocumentImageProcessor.subsamplingStep(20_000, 2500)).isEqualTo(2);
    }

    @Test
    @DisplayName("the orientation comes from a JPEG's EXIF segment only: a PNG is never parsed for metadata, so a "
            + "compressed text chunk that inflates to gigabytes cannot exhaust the memory")
    void metadataOfJpegOnly() throws IOException {
        BufferedImage image = new BufferedImage(8, 8, BufferedImage.TYPE_INT_RGB);

        assertThat(DocumentImageProcessor.orientation(withChunk(png(image), "eXIf", tiffOrientation(6))))
                .as("a PNG's own EXIF chunk is not read").isEqualTo(1);
        assertThat(DocumentImageProcessor.orientation(withOrientation(jpeg(image), 6))).isEqualTo(6);
    }

    @Test
    @DisplayName("each EXIF orientation turns the stored corners where they belong")
    void uprightTransforms() {
        // A stored 4 x 2 image: where do its top-left and top-right corners go?
        assertThat(corner(1, 0, 0)).isEqualTo(new Point2D.Double(0, 0));
        assertThat(corner(3, 0, 0)).as("half a turn").isEqualTo(new Point2D.Double(4, 2));
        assertThat(corner(6, 0, 0)).as("a quarter clockwise").isEqualTo(new Point2D.Double(2, 0));
        assertThat(corner(6, 4, 0)).isEqualTo(new Point2D.Double(2, 4));
        assertThat(corner(8, 0, 0)).as("a quarter anticlockwise").isEqualTo(new Point2D.Double(0, 4));
        assertThat(corner(2, 0, 0)).as("mirrored").isEqualTo(new Point2D.Double(4, 0));
        assertThat(corner(5, 4, 0)).as("transposed").isEqualTo(new Point2D.Double(0, 4));
    }

    // ------------------------------------------------------------------------------------------------------------

    private static Point2D corner(int orientation, double x, double y) {
        AffineTransform transform = DocumentImageProcessor.upright(orientation, 4, 2);
        return transform.transform(new Point2D.Double(x, y), null);
    }

    private static FileUpload upload(byte[] bytes, String type) {
        return new FileUpload("note.jpg", type, bytes.length, new ByteArrayInputStream(bytes));
    }

    private static InputStream unreadable() {
        return new InputStream() {
            @Override
            public int read() {
                throw new AssertionError("the size was declared too large: nothing should be read");
            }
        };
    }

    private static ErrorCode refusal(ThrowingCallable call) {
        Throwable thrown = catchThrowable(call);
        assertThat(thrown).isInstanceOf(BusinessException.class);
        return ((BusinessException) thrown).errorCode();
    }

    static byte[] jpeg(BufferedImage image) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        assertThat(ImageIO.write(image, "jpeg", out)).isTrue();
        return out.toByteArray();
    }

    static byte[] png(BufferedImage image) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        assertThat(ImageIO.write(image, "png", out)).isTrue();
        return out.toByteArray();
    }

    /** The JPEG with an EXIF segment holding only an orientation, after its JFIF header, as a phone writes one. */
    static byte[] withOrientation(byte[] jpeg, int orientation) {
        ByteBuffer exif = ByteBuffer.allocate(36);
        exif.put(new byte[] {(byte) 0xFF, (byte) 0xE1}).putShort((short) 34)
                .put("Exif\0\0".getBytes(StandardCharsets.US_ASCII))
                // TIFF header, big-endian, IFD0 at offset 8: one entry, Orientation (0x0112), SHORT, count 1.
                .put(new byte[] {'M', 'M', 0, 42, 0, 0, 0, 8})
                .putShort((short) 1)
                .putShort((short) 0x0112).putShort((short) 3).putInt(1).putShort((short) orientation).putShort((short) 0)
                .putInt(0);
        int at = 2;
        if ((jpeg[2] & 0xFF) == 0xFF && (jpeg[3] & 0xFF) == 0xE0) {
            at = 4 + (((jpeg[4] & 0xFF) << 8) | (jpeg[5] & 0xFF));
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(jpeg, 0, at);
        out.write(exif.array(), 0, exif.position());
        out.write(jpeg, at, jpeg.length - at);
        return out.toByteArray();
    }

    /** A TIFF block, big-endian, whose IFD0 holds one entry: Orientation (0x0112), SHORT, count 1. */
    private static byte[] tiffOrientation(int orientation) {
        return ByteBuffer.allocate(26).put(new byte[] {'M', 'M', 0, 42, 0, 0, 0, 8}).putShort((short) 1)
                .putShort((short) 0x0112).putShort((short) 3).putInt(1).putShort((short) orientation).putShort((short) 0)
                .putInt(0).array();
    }

    /** The PNG with one more chunk right before its IEND chunk, which is its last twelve bytes. */
    private static byte[] withChunk(byte[] png, String type, byte[] data) {
        ByteBuffer chunk = ByteBuffer.allocate(12 + data.length).putInt(data.length)
                .put(type.getBytes(StandardCharsets.US_ASCII)).put(data);
        CRC32 crc = new CRC32();
        crc.update(chunk.array(), 4, 4 + data.length);
        chunk.putInt((int) crc.getValue());
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(png, 0, png.length - 12);
        out.write(chunk.array(), 0, chunk.position());
        out.write(png, png.length - 12, 12);
        return out.toByteArray();
    }

    /** A PNG that claims {@code width} x {@code height} pixels and holds none of them. */
    private static byte[] pngHeader(int width, int height) {
        ByteBuffer ihdr = ByteBuffer.allocate(17).put("IHDR".getBytes(StandardCharsets.US_ASCII))
                .putInt(width).putInt(height).put(new byte[] {8, 2, 0, 0, 0});
        CRC32 crc = new CRC32();
        crc.update(ihdr.array(), 0, 17);
        ByteBuffer png = ByteBuffer.allocate(8 + 4 + 17 + 4 + 12);
        png.put(new byte[] {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A})
                .putInt(13).put(ihdr.array()).putInt((int) crc.getValue())
                .putInt(0).put("IEND".getBytes(StandardCharsets.US_ASCII)).putInt(0xAE426082);
        return png.array();
    }

    private static boolean contains(byte[] bytes, String text) {
        return new String(bytes, StandardCharsets.ISO_8859_1).contains(text);
    }

    private static int luminance(int rgb) {
        Color color = new Color(rgb);
        return (color.getRed() * 299 + color.getGreen() * 587 + color.getBlue() * 114) / 1000;
    }
}
