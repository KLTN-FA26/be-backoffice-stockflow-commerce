package com.stockflow.product.internal.service;

import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.storage.ContentTypePolicy;
import com.stockflow.common.storage.FileCategory;
import com.stockflow.common.storage.FileUpload;
import org.springframework.stereotype.Component;
import javax.imageio.ImageIO;
import javax.imageio.IIOImage;
import javax.imageio.ImageWriteParam;
import javax.imageio.stream.MemoryCacheImageInputStream;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Semaphore;

/**
 * Decode limits apply before allocating pixels; a bounded gate also limits concurrent decoders.
 *
 * <p>Peak heap per decode is the decoded raster (4 bytes a pixel) plus the rotated copy, so it is
 * bounded twice: images above {@link #MAX_PIXELS} are refused, and anything above
 * {@link #DECODE_PIXEL_BUDGET} is read with source subsampling, never below the largest rendition.
 * Two decoders at 8 MP is about 130 MB; two at 40 MP with no subsampling was over 600 MB.</p>
 */
@Component
public class ProductImageProcessor {
    static final long MAX_PIXELS = 25_000_000L;
    static final double DECODE_PIXEL_BUDGET = 8_000_000d;
    static final int LARGEST_RENDITION_EDGE = 1600;
    private final Semaphore decoders = new Semaphore(2);
    public record Rendered(int edge, int width, int height, String contentType, byte[] bytes) { }
    public record Prepared(byte[] original, List<Rendered> renditions) { }

    public Prepared prepare(FileUpload upload) {
        if (!decoders.tryAcquire()) { throw new BusinessException(ErrorCode.RATE_LIMITED, "Image processing is busy; retry later"); }
        try {
            int limit = Math.toIntExact(FileCategory.PRODUCT_IMAGE.maxBytes());
            ContentTypePolicy.check(FileCategory.PRODUCT_IMAGE, upload.contentType(), upload.sizeBytes(), new byte[0]);
            byte[] bytes = upload.content().readNBytes(limit + 1);
            ContentTypePolicy.check(FileCategory.PRODUCT_IMAGE, upload.contentType(), bytes.length,
                    Arrays.copyOf(bytes, Math.min(16, bytes.length)));
            if (bytes.length != upload.sizeBytes()) { invalid("Declared file size differs from received bytes"); }
            try (var input = new MemoryCacheImageInputStream(new ByteArrayInputStream(bytes))) {
                var readers = ImageIO.getImageReaders(input);
                if (!readers.hasNext()) { invalid("Image cannot be decoded"); }
                var reader = readers.next();
                try {
                    reader.setInput(input);
                    int width = reader.getWidth(0), height = reader.getHeight(0);
                    if (width < 1 || height < 1 || (long) width * height > MAX_PIXELS) {
                        throw new BusinessException(ErrorCode.PAYLOAD_TOO_LARGE, "Image exceeds the 25 megapixel decode limit");
                    }
                    var param = reader.getDefaultReadParam();
                    int step = subsamplingStep(width, height);
                    if (step > 1) { param.setSourceSubsampling(step, step, 0, 0); }
                    BufferedImage source = orient(reader.read(0, param), orientation(bytes));
                    var results = new ArrayList<Rendered>();
                    for (int edge : new int[] {256, 768, 1600}) { results.add(resize(source, edge)); }
                    source.flush();
                    return new Prepared(bytes, List.copyOf(results));
                } finally { reader.dispose(); }
            }
        } catch (IOException ex) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "Image is damaged or unreadable");
        } finally { decoders.release(); }
    }

    /** Smallest step that brings the decode under budget while keeping the largest rendition sharp. */
    static int subsamplingStep(int width, int height) {
        int forBudget = (int) Math.ceil(Math.sqrt((double) width * height / DECODE_PIXEL_BUDGET));
        int keepsSharpness = Math.max(1, Math.max(width, height) / LARGEST_RENDITION_EDGE);
        return Math.max(1, Math.min(forBudget, keepsSharpness));
    }

    private Rendered resize(BufferedImage source, int edge) throws IOException {
        double scale = Math.min(1.0, (double) edge / Math.max(source.getWidth(), source.getHeight()));
        int width = Math.max(1, (int) Math.round(source.getWidth() * scale));
        int height = Math.max(1, (int) Math.round(source.getHeight() * scale));
        boolean alpha = source.getColorModel().hasAlpha();
        var target = new BufferedImage(width, height, alpha ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB);
        var graphics = target.createGraphics();
        try {
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            graphics.drawImage(source, 0, 0, width, height, null);
        } finally { graphics.dispose(); }
        String format = alpha ? "png" : "jpeg";
        var writer = ImageIO.getImageWritersByFormatName(format).next();
        var bytes = new ByteArrayOutputStream();
        try (var output = new javax.imageio.stream.MemoryCacheImageOutputStream(bytes)) {
            writer.setOutput(output);
            var params = writer.getDefaultWriteParam();
            if (!alpha) { params.setCompressionMode(ImageWriteParam.MODE_EXPLICIT); params.setCompressionQuality(0.85f); }
            // No source metadata is copied to customer-facing files.
            writer.write(null, new IIOImage(target, null, null), params);
        } finally { writer.dispose(); target.flush(); }
        return new Rendered(edge, width, height, "image/" + format, bytes.toByteArray());
    }
    private static void invalid(String message) { throw new BusinessException(ErrorCode.VALIDATION_FAILED, message); }

    private static int orientation(byte[] bytes) throws IOException {
        try {
            var metadata = com.drew.imaging.ImageMetadataReader.readMetadata(new ByteArrayInputStream(bytes));
            var exif = metadata.getFirstDirectoryOfType(com.drew.metadata.exif.ExifIFD0Directory.class);
            Integer orientation = exif == null ? null : exif.getInteger(com.drew.metadata.exif.ExifIFD0Directory.TAG_ORIENTATION);
            return orientation == null ? 1 : orientation;
        } catch (com.drew.imaging.ImageProcessingException ex) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "Image metadata cannot be decoded");
        }
    }

    static BufferedImage orient(BufferedImage source, int orientation) {
        if (orientation <= 1 || orientation > 8) { return source; }
        int width = source.getWidth(), height = source.getHeight();
        var target = new BufferedImage(orientation >= 5 ? height : width, orientation >= 5 ? width : height,
                source.getColorModel().hasAlpha() ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int dx = x, dy = y;
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
