package com.stockflow.assistant.internal.service;

import com.drew.imaging.jpeg.JpegMetadataReader;
import com.drew.metadata.Metadata;
import com.drew.metadata.exif.ExifIFD0Directory;
import com.drew.metadata.exif.ExifReader;
import com.stockflow.assistant.internal.domain.DocumentReader;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.storage.FileUpload;
import com.stockflow.common.storage.StorageException;
import org.springframework.stereotype.Component;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageReadParam;
import javax.imageio.ImageReader;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageInputStream;
import javax.imageio.stream.ImageOutputStream;
import javax.imageio.stream.MemoryCacheImageInputStream;
import javax.imageio.stream.MemoryCacheImageOutputStream;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/**
 * Accepts a photographed document and prepares the copy that is sent to the model provider (design §8.1).
 *
 * <p><b>The bytes decide what a file is.</b> As in {@code ContentTypePolicy}, which the stored uploads go through, the
 * declared type must be one of the accepted ones and the first bytes must be that type: a name ending in
 * {@code .jpg}, or a header saying {@code image/jpeg}, is the client's word. That policy is not reused as is: its
 * categories are where a stored file goes, and this file is stored nowhere.</p>
 *
 * <p><b>What is sent is a fresh JPEG, never the upload.</b> The photo is decoded, turned upright by its EXIF
 * orientation, scaled so its long edge is at most {@value #MAX_EDGE} pixels, and encoded again without any metadata.
 * Four reasons. A phone photo carries GPS coordinates, the phone's make and the time in its EXIF, which the provider
 * has no use for (design §13 AQ-01). A portrait photo is stored sideways with a tag saying so, which a provider may or
 * may not honour, and a model reads sideways print worse. Claude refuses an image above 5 MB as encoded in the request,
 * so an upload near the {@value #MAX_BYTES}-byte limit would come back as a provider refusal that is really the
 * photo's size: what is sent stays under {@value #MAX_SENT_BYTES} bytes, a third more in base64, by lowering the
 * quality for the rare photo that needs it. And pixels beyond the model's own maximum (2576 on the long edge for
 * Claude's current models) are scaled away by the provider anyway, after costing upload time. A file that does not
 * decode is refused here, as the user's 400, before anything leaves the application.</p>
 *
 * <p><b>Decoding is bounded</b>, as in the product images' processor: an image above {@value #MAX_PIXELS} pixels is
 * refused before its pixels are allocated, a large one is decoded with source subsampling down to about
 * {@code DECODE_PIXEL_BUDGET} pixels (never below the edge that is sent), and at most two are decoded at a time. A
 * third waits up to {@value #DECODER_WAIT_SECONDS} seconds for its turn, short beside the model call that follows,
 * before it is refused as busy.</p>
 */
@Component
class DocumentImageProcessor {

    /** The largest upload accepted (contract C9). */
    static final long MAX_BYTES = 5L * 1024 * 1024;

    /** Above this an image is refused unread: a 48-megapixel phone photo passes, a decompression bomb does not. */
    static final long MAX_PIXELS = 50_000_000L;

    /** The long edge that is sent: Claude's maximum resolution; Gemini tiles the image whatever its size. */
    static final int MAX_EDGE = 2576;

    /** About this many pixels are decoded at most; about 64 MB of raster at four bytes a pixel. */
    static final double DECODE_PIXEL_BUDGET = 16_000_000d;

    /**
     * The qualities tried, best first: 0.9 keeps small print through the second encoding, and a document photo is
     * well under {@value #MAX_SENT_BYTES} bytes at it; the lower ones are for a photo so detailed that it is not.
     */
    static final float[] JPEG_QUALITIES = {0.9f, 0.75f, 0.6f};

    /** The most that is sent: 3.5 MB, under 5 MB once base64 has added its third. */
    static final int MAX_SENT_BYTES = 3_670_016;

    static final int DECODER_WAIT_SECONDS = 5;

    static final String SENT_TYPE = "image/jpeg";

    /** The types accepted, in the order a refusal names them. */
    static final List<String> ACCEPTED = List.of("image/jpeg", "image/png", "image/webp");

    private final Semaphore decoders = new Semaphore(2);

    /**
     * The copy that is sent.
     *
     * @param width  pixels after orientation and scaling
     * @param height the same
     */
    record Prepared(DocumentReader.Image image, int width, int height) {
    }

    /**
     * The upload's bytes, once they are known to be an accepted image of at most {@value #MAX_BYTES} bytes: 413 above
     * that, 400 when empty, 415 when the declared type is not accepted or the bytes are not that type. Reads at most
     * one byte past the limit, whatever the upload claims its size is.
     */
    byte[] accept(FileUpload upload) {
        if (upload.sizeBytes() > MAX_BYTES) {
            throw tooLarge();
        }
        byte[] bytes;
        try {
            bytes = upload.content().readNBytes((int) MAX_BYTES + 1);
        } catch (IOException unreadable) {
            throw new StorageException("Could not read the uploaded photo", unreadable);
        }
        if (bytes.length > MAX_BYTES) {
            throw tooLarge();
        }
        if (bytes.length == 0) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "The uploaded photo is empty");
        }
        requireImage(bytes, upload.contentType());
        return bytes;
    }

    /** The declared type is accepted and the first bytes are that type; refused with 415 otherwise. */
    static void requireImage(byte[] bytes, String declaredType) {
        String declared = declaredType == null ? "" : declaredType.split(";", 2)[0].strip().toLowerCase(Locale.ROOT);
        if (!ACCEPTED.contains(declared)) {
            throw new BusinessException(ErrorCode.UNSUPPORTED_MEDIA_TYPE, "A delivery-note photo is JPEG, PNG or WEBP; "
                    + "this upload is declared as " + (declared.isEmpty() ? "nothing" : printable(declared)));
        }
        if (!declared.equals(sniff(bytes))) {
            throw new BusinessException(ErrorCode.UNSUPPORTED_MEDIA_TYPE,
                    "The file's contents are not the " + declared + " it is declared as");
        }
    }

    /** The accepted type the first bytes are, or null. */
    static String sniff(byte[] head) {
        if (startsWith(head, 0xFF, 0xD8, 0xFF)) {
            return "image/jpeg";
        }
        if (startsWith(head, 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)) {
            return "image/png";
        }
        // "RIFF", four bytes of length, then "WEBP".
        if (startsWith(head, 0x52, 0x49, 0x46, 0x46) && head.length >= 12
                && head[8] == 'W' && head[9] == 'E' && head[10] == 'B' && head[11] == 'P') {
            return "image/webp";
        }
        return null;
    }

    /**
     * The upright, scaled JPEG of an accepted image.
     *
     * @throws BusinessException {@code VALIDATION_FAILED} when it does not decode, {@code PAYLOAD_TOO_LARGE} above
     *         {@value #MAX_PIXELS} pixels or when no quality brings it under {@value #MAX_SENT_BYTES} bytes,
     *         {@code RATE_LIMITED} when two others are still being decoded after the wait
     */
    Prepared prepare(byte[] bytes) {
        try {
            if (!decoders.tryAcquire(DECODER_WAIT_SECONDS, TimeUnit.SECONDS)) {
                throw new BusinessException(ErrorCode.RATE_LIMITED,
                        "Other photos are being read; try again in a moment");
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new BusinessException(ErrorCode.RATE_LIMITED, "Interrupted waiting to read the photo", interrupted);
        }
        try (ImageInputStream input = new MemoryCacheImageInputStream(new ByteArrayInputStream(bytes))) {
            Iterator<ImageReader> readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) {
                throw unreadable(null);
            }
            ImageReader reader = readers.next();
            try {
                // Metadata is read once, below, for the orientation only.
                reader.setInput(input, true, true);
                int width = reader.getWidth(0);
                int height = reader.getHeight(0);
                if (width < 1 || height < 1) {
                    throw unreadable(null);
                }
                if ((long) width * height > MAX_PIXELS) {
                    throw new BusinessException(ErrorCode.PAYLOAD_TOO_LARGE, "The photo has %d x %d pixels; at most "
                            .formatted(width, height) + MAX_PIXELS / 1_000_000 + " megapixels are read");
                }
                ImageReadParam param = reader.getDefaultReadParam();
                int step = subsamplingStep(width, height);
                if (step > 1) {
                    param.setSourceSubsampling(step, step, 0, 0);
                }
                BufferedImage decoded = reader.read(0, param);
                try {
                    return render(decoded, orientation(bytes));
                } finally {
                    decoded.flush();
                }
            } finally {
                reader.dispose();
            }
        } catch (BusinessException refused) {
            throw refused;
        } catch (IOException | RuntimeException damaged) {
            // A decoder meeting damaged data may throw almost anything; to the user it is one thing.
            throw unreadable(damaged);
        } finally {
            decoders.release();
        }
    }

    /** The smallest step that brings the decode under the budget, never one that drops below the edge sent. */
    static int subsamplingStep(int width, int height) {
        int forBudget = (int) Math.ceil(Math.sqrt((double) width * height / DECODE_PIXEL_BUDGET));
        int keepsDetail = Math.max(1, Math.max(width, height) / MAX_EDGE);
        return Math.max(1, Math.min(forBudget, keepsDetail));
    }

    /**
     * Draws the decoded image upright and scaled onto a white canvas, in one pass, and encodes it. White, because a
     * transparent PNG or WEBP is dark print on nothing, which reads as dark print on black once the alpha is gone.
     */
    static Prepared render(BufferedImage source, int orientation) throws IOException {
        boolean turned = orientation >= 5 && orientation <= 8;
        int uprightWidth = turned ? source.getHeight() : source.getWidth();
        int uprightHeight = turned ? source.getWidth() : source.getHeight();
        double scale = Math.min(1.0, (double) MAX_EDGE / Math.max(uprightWidth, uprightHeight));
        int width = Math.max(1, (int) Math.round(uprightWidth * scale));
        int height = Math.max(1, (int) Math.round(uprightHeight * scale));

        BufferedImage target = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = target.createGraphics();
        try {
            graphics.setColor(Color.WHITE);
            graphics.fillRect(0, 0, width, height);
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            AffineTransform transform = AffineTransform.getScaleInstance((double) width / uprightWidth,
                    (double) height / uprightHeight);
            transform.concatenate(upright(orientation, source.getWidth(), source.getHeight()));
            graphics.drawImage(source, transform, null);
        } finally {
            graphics.dispose();
        }
        try {
            for (float quality : JPEG_QUALITIES) {
                byte[] jpeg = jpeg(target, quality);
                if (jpeg.length <= MAX_SENT_BYTES) {
                    return new Prepared(new DocumentReader.Image(jpeg, SENT_TYPE), width, height);
                }
            }
            throw new BusinessException(ErrorCode.PAYLOAD_TOO_LARGE, "The photo is too detailed to send even at the "
                    + "lowest quality; take it again, closer to the paper");
        } finally {
            target.flush();
        }
    }

    /**
     * What turns a stored image of {@code width} x {@code height} upright, by its EXIF orientation: 2 and 4 mirror, 3
     * turns half round, 6 and 8 turn a quarter clockwise and anticlockwise, 5 and 7 are those mirrored. Anything else
     * is upright already.
     */
    static AffineTransform upright(int orientation, int width, int height) {
        return switch (orientation) {
            case 2 -> new AffineTransform(-1, 0, 0, 1, width, 0);
            case 3 -> new AffineTransform(-1, 0, 0, -1, width, height);
            case 4 -> new AffineTransform(1, 0, 0, -1, 0, height);
            case 5 -> new AffineTransform(0, 1, 1, 0, 0, 0);
            case 6 -> new AffineTransform(0, 1, -1, 0, height, 0);
            case 7 -> new AffineTransform(0, -1, -1, 0, height, width);
            case 8 -> new AffineTransform(0, -1, 1, 0, 0, width);
            default -> new AffineTransform();
        };
    }

    /**
     * The EXIF orientation, 1 to 8; 1 when there is none or the metadata cannot be read.
     *
     * <p><b>A JPEG's EXIF segment only.</b> Phones write the orientation there, in an APP1 segment the format bounds to
     * 64 KB. The library's whole-file reader would also inflate a PNG's compressed text chunks, with no bound: a 600 KB
     * upload can hold 600 MB of text, and the OutOfMemoryError ends the whole application, which runs with
     * {@code -XX:+ExitOnOutOfMemoryError}. A PNG or WEBP is sent the way it is stored.</p>
     */
    static int orientation(byte[] bytes) {
        if (!"image/jpeg".equals(sniff(bytes))) {
            return 1;
        }
        try {
            Metadata metadata = JpegMetadataReader.readMetadata(new ByteArrayInputStream(bytes),
                    List.of(new ExifReader()));
            ExifIFD0Directory exif = metadata.getFirstDirectoryOfType(ExifIFD0Directory.class);
            Integer orientation = exif == null ? null : exif.getInteger(ExifIFD0Directory.TAG_ORIENTATION);
            return orientation == null || orientation < 1 || orientation > 8 ? 1 : orientation;
        } catch (Exception unreadable) {
            // A photo whose metadata cannot be read is still a photo: sent the way it is stored.
            return 1;
        }
    }

    static byte[] jpeg(BufferedImage image, float quality) throws IOException {
        ImageWriter writer = ImageIO.getImageWritersByFormatName("jpeg").next();
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ImageOutputStream output = new MemoryCacheImageOutputStream(bytes)) {
            writer.setOutput(output);
            ImageWriteParam param = writer.getDefaultWriteParam();
            param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            param.setCompressionQuality(quality);
            // No metadata: nothing of the upload's EXIF goes out with the pixels.
            writer.write(null, new IIOImage(image, null, null), param);
        } finally {
            writer.dispose();
        }
        return bytes.toByteArray();
    }

    private static boolean startsWith(byte[] head, int... signature) {
        if (head.length < signature.length) {
            return false;
        }
        for (int i = 0; i < signature.length; i++) {
            if ((head[i] & 0xFF) != signature[i]) {
                return false;
            }
        }
        return true;
    }

    /** A client-supplied type, safe to quote back: no more than a type's own characters, and not much of them. */
    private static String printable(String type) {
        String safe = type.replaceAll("[^a-z0-9.+/-]", "?");
        return safe.length() > 60 ? safe.substring(0, 60) + "…" : safe;
    }

    private static BusinessException tooLarge() {
        return new BusinessException(ErrorCode.PAYLOAD_TOO_LARGE,
                "A delivery-note photo may be at most " + MAX_BYTES / (1024 * 1024) + " MB");
    }

    private static BusinessException unreadable(Throwable cause) {
        return new BusinessException(ErrorCode.VALIDATION_FAILED, "The photo cannot be decoded as an image", cause);
    }
}
