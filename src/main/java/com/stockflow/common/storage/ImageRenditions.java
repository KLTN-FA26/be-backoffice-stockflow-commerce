package com.stockflow.common.storage;

import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import org.springframework.stereotype.Component;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.stream.MemoryCacheImageInputStream;
import java.awt.Color;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/**
 * Re-encoded display copies of an uploaded image: turned upright from its EXIF orientation, scaled to each requested
 * longest edge, and written without any of the source's metadata - no camera, no time, no GPS position.
 *
 * <p>Two reasons to show these rather than the upload. A photo straight from a phone carries where it was taken, which
 * for a customer's return photo is their home: personal data nobody viewing a defect needs. And a re-encoded image is
 * only pixels, so whatever else the original file carried (a polyglot payload, a malformed chunk aimed at a viewer)
 * stays in the private original.</p>
 *
 * <h2>Memory</h2>
 *
 * <p>Peak heap per decode is the decoded raster (4 bytes a pixel) plus the rotated copy, so it is bounded twice:
 * images above {@link #MAX_PIXELS} are refused, and anything above {@link #DECODE_PIXEL_BUDGET} is read with source
 * subsampling, never below the largest rendition. Two decoders at 8 MP is about 130 MB; two at 40 MP with no
 * subsampling was over 600 MB. One gate for every caller - product images and evidence photos - so the bound holds for
 * the whole instance; a decode over the limit waits for a slot rather than failing at once.</p>
 */
@Component
public class ImageRenditions {

    static final long MAX_PIXELS = 25_000_000L;
    static final double DECODE_PIXEL_BUDGET = 8_000_000d;
    private static final Duration WAIT_FOR_DECODER = Duration.ofSeconds(15);

    private final Semaphore decoders = new Semaphore(2);

    /** One display copy. */
    public record Rendition(int edge, int width, int height, String contentType, byte[] bytes) {
    }

    /**
     * Display copies of {@code bytes} at each longest edge, in the order given.
     *
     * @param flatten write JPEG always, painting transparency white; otherwise an image with transparency stays PNG
     * @throws BusinessException {@code VALIDATION_FAILED} for bytes no reader decodes, {@code PAYLOAD_TOO_LARGE}
     *         above {@link #MAX_PIXELS}, {@code RATE_LIMITED} when no decoder frees up in time
     */
    public List<Rendition> render(byte[] bytes, boolean flatten, int... edges) {
        int largest = 0;
        for (int edge : edges) {
            largest = Math.max(largest, edge);
        }
        acquire();
        try (var input = new MemoryCacheImageInputStream(new ByteArrayInputStream(bytes))) {
            var readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) {
                throw new BusinessException(ErrorCode.VALIDATION_FAILED, "Image cannot be decoded");
            }
            var reader = readers.next();
            try {
                reader.setInput(input);
                int width = reader.getWidth(0);
                int height = reader.getHeight(0);
                if (width < 1 || height < 1 || (long) width * height > MAX_PIXELS) {
                    throw new BusinessException(ErrorCode.PAYLOAD_TOO_LARGE, "Image exceeds the 25 megapixel decode limit");
                }
                var param = reader.getDefaultReadParam();
                int step = subsamplingStep(width, height, largest);
                if (step > 1) {
                    param.setSourceSubsampling(step, step, 0, 0);
                }
                BufferedImage source = orient(reader.read(0, param), orientation(bytes));
                List<Rendition> results = new ArrayList<>();
                for (int edge : edges) {
                    results.add(resize(source, edge, flatten));
                }
                source.flush();
                return List.copyOf(results);
            } finally {
                reader.dispose();
            }
        } catch (IOException | RuntimeException ex) {
            if (ex instanceof BusinessException business) {
                throw business;
            }
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "Image is damaged or unreadable");
        } finally {
            decoders.release();
        }
    }

    private void acquire() {
        try {
            if (!decoders.tryAcquire(WAIT_FOR_DECODER.toMillis(), TimeUnit.MILLISECONDS)) {
                throw new BusinessException(ErrorCode.RATE_LIMITED, "Image processing is busy; retry later");
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new BusinessException(ErrorCode.RATE_LIMITED, "Image processing is busy; retry later");
        }
    }

    /** Smallest step that brings the decode under budget while keeping the largest rendition sharp. */
    public static int subsamplingStep(int width, int height, int largestEdge) {
        int forBudget = (int) Math.ceil(Math.sqrt((double) width * height / DECODE_PIXEL_BUDGET));
        int keepsSharpness = Math.max(1, Math.max(width, height) / Math.max(1, largestEdge));
        return Math.max(1, Math.min(forBudget, keepsSharpness));
    }

    private static Rendition resize(BufferedImage source, int edge, boolean flatten) throws IOException {
        double scale = Math.min(1.0, (double) edge / Math.max(source.getWidth(), source.getHeight()));
        int width = Math.max(1, (int) Math.round(source.getWidth() * scale));
        int height = Math.max(1, (int) Math.round(source.getHeight() * scale));
        boolean alpha = source.getColorModel().hasAlpha() && !flatten;
        var target = new BufferedImage(width, height, alpha ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB);
        var graphics = target.createGraphics();
        try {
            if (!alpha) {
                graphics.setColor(Color.WHITE);
                graphics.fillRect(0, 0, width, height);
            }
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            graphics.drawImage(source, 0, 0, width, height, null);
        } finally {
            graphics.dispose();
        }
        String format = alpha ? "png" : "jpeg";
        var writer = ImageIO.getImageWritersByFormatName(format).next();
        var bytes = new ByteArrayOutputStream();
        try (var output = new javax.imageio.stream.MemoryCacheImageOutputStream(bytes)) {
            writer.setOutput(output);
            var params = writer.getDefaultWriteParam();
            if (!alpha) {
                params.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
                params.setCompressionQuality(0.85f);
            }
            // No source metadata is copied: the point of a rendition.
            writer.write(null, new IIOImage(target, null, null), params);
        } finally {
            writer.dispose();
            target.flush();
        }
        return new Rendition(edge, width, height, "image/" + format, bytes.toByteArray());
    }

    private static int orientation(byte[] bytes) throws IOException {
        try {
            var metadata = com.drew.imaging.ImageMetadataReader.readMetadata(new ByteArrayInputStream(bytes));
            var exif = metadata.getFirstDirectoryOfType(com.drew.metadata.exif.ExifIFD0Directory.class);
            Integer orientation = exif == null ? null
                    : exif.getInteger(com.drew.metadata.exif.ExifIFD0Directory.TAG_ORIENTATION);
            return orientation == null ? 1 : orientation;
        } catch (com.drew.imaging.ImageProcessingException ex) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "Image metadata cannot be decoded");
        }
    }

    /** {@code source} turned upright from an EXIF orientation (1 to 8). */
    public static BufferedImage orient(BufferedImage source, int orientation) {
        if (orientation <= 1 || orientation > 8) {
            return source;
        }
        int width = source.getWidth();
        int height = source.getHeight();
        var target = new BufferedImage(orientation >= 5 ? height : width, orientation >= 5 ? width : height,
                source.getColorModel().hasAlpha() ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int dx = x;
                int dy = y;
                switch (orientation) {
                    case 2 -> dx = width - 1 - x;
                    case 3 -> { dx = width - 1 - x; dy = height - 1 - y; }
                    case 4 -> dy = height - 1 - y;
                    case 5 -> { dx = y; dy = x; }
                    case 6 -> { dx = height - 1 - y; dy = x; }
                    case 7 -> { dx = height - 1 - y; dy = width - 1 - x; }
                    case 8 -> { dx = y; dy = width - 1 - x; }
                    default -> { }
                }
                target.setRGB(dx, dy, source.getRGB(x, y));
            }
        }
        source.flush();
        return target;
    }
}
