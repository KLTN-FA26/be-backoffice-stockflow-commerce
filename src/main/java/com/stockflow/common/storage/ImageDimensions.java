package com.stockflow.common.storage;

import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;

import javax.imageio.ImageIO;
import javax.imageio.stream.FileImageInputStream;
import javax.imageio.stream.ImageInputStream;
import javax.imageio.stream.MemoryCacheImageInputStream;
import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * The size an image is shown at - after its EXIF orientation, so a portrait photo taken sideways measures as a portrait -
 * read from its header without decoding a single pixel. What the design studio's print-resolution check needs
 * (kltn-docs 12 BR-05: an image placed at a given size must reach the template's minimum DPI): the browser draws the
 * image upright, so the width the customer sized is the oriented width.
 *
 * <p>Measuring is cheap, so it is not behind {@link ImageRenditions}' decoder gate; it is bounded instead by refusing
 * anything over {@link #MAX_PIXELS}. That is larger than the renditions' decode limit on purpose: a design asset is
 * stored and printed, never decoded here.</p>
 */
public final class ImageDimensions {

    /** 40 megapixels: more than any print a cup or bag needs, and a decompression bomb's size is refused unread. */
    public static final long MAX_PIXELS = 40_000_000L;

    private ImageDimensions() {
    }

    /**
     * Whether uploads of {@code category} are measured when they are prepared - before they are stored, so an image
     * over {@link #MAX_PIXELS} is refused without ever reaching the store. A customer's design image is: the studio's
     * print-resolution check needs its size from the server, not from the browser that sent it.
     */
    public static boolean measures(FileCategory category) {
        return category == FileCategory.DESIGN_ASSET;
    }

    /** An image's upright size in pixels. */
    public record Size(int width, int height) {
        public long pixels() {
            return (long) width * height;
        }
    }

    /**
     * The upright size of the image in {@code file}.
     *
     * @throws BusinessException {@code VALIDATION_FAILED} for bytes no reader understands, {@code PAYLOAD_TOO_LARGE}
     *         above {@link #MAX_PIXELS}
     */
    public static Size of(Path file) {
        try (ImageInputStream input = new FileImageInputStream(file.toFile());
             InputStream metadata = new BufferedInputStream(Files.newInputStream(file))) {
            return measure(input, metadata);
        } catch (IOException ex) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "Image is damaged or unreadable");
        }
    }

    /** {@link #of(Path)} of bytes already in memory. */
    public static Size of(byte[] bytes) {
        try (ImageInputStream input = new MemoryCacheImageInputStream(new ByteArrayInputStream(bytes))) {
            return measure(input, new ByteArrayInputStream(bytes));
        } catch (IOException ex) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "Image is damaged or unreadable");
        }
    }

    private static Size measure(ImageInputStream input, InputStream metadata) throws IOException {
        var readers = ImageIO.getImageReaders(input);
        if (!readers.hasNext()) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "Image cannot be decoded");
        }
        var reader = readers.next();
        int width;
        int height;
        try {
            reader.setInput(input, true, true);
            width = reader.getWidth(0);
            height = reader.getHeight(0);
        } catch (RuntimeException ex) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "Image is damaged or unreadable");
        } finally {
            reader.dispose();
        }
        if (width < 1 || height < 1) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "Image has no pixels");
        }
        if ((long) width * height > MAX_PIXELS) {
            throw new BusinessException(ErrorCode.PAYLOAD_TOO_LARGE, "Image exceeds the 40 megapixel limit");
        }
        return orientation(metadata) >= 5 ? new Size(height, width) : new Size(width, height);
    }

    /**
     * The EXIF orientation of an image, 1 (upright) when it carries none. Values 5 to 8 are a quarter turn, which swaps
     * width and height.
     *
     * @throws BusinessException {@code VALIDATION_FAILED} when the metadata itself cannot be parsed
     */
    static int orientation(InputStream content) throws IOException {
        try {
            var metadata = com.drew.imaging.ImageMetadataReader.readMetadata(content);
            var exif = metadata.getFirstDirectoryOfType(com.drew.metadata.exif.ExifIFD0Directory.class);
            Integer orientation = exif == null ? null
                    : exif.getInteger(com.drew.metadata.exif.ExifIFD0Directory.TAG_ORIENTATION);
            return orientation == null || orientation < 1 || orientation > 8 ? 1 : orientation;
        } catch (com.drew.imaging.ImageProcessingException ex) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "Image metadata cannot be decoded");
        }
    }
}
