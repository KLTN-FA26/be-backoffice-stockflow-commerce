package com.stockflow.product.internal.service;

import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.storage.ContentTypePolicy;
import com.stockflow.common.storage.FileCategory;
import com.stockflow.common.storage.FileUpload;
import com.stockflow.common.storage.ImageRenditions;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.Arrays;
import java.util.List;

/**
 * A product photograph read whole and checked, then rendered at the storefront's three sizes.
 *
 * <p>The decoding, orientation, scaling and metadata stripping are the platform's ({@link ImageRenditions}), shared
 * with evidence photos so that one gate bounds the decoders' heap for the whole instance.</p>
 */
@Component
public class ProductImageProcessor {
    static final int LARGEST_RENDITION_EDGE = 1600;
    private static final int[] EDGES = {256, 768, LARGEST_RENDITION_EDGE};

    private final ImageRenditions renditions;

    public record Rendered(int edge, int width, int height, String contentType, byte[] bytes) { }
    public record Prepared(byte[] original, List<Rendered> renditions) { }

    /** On its own renderer: the shape the unit tests construct. */
    public ProductImageProcessor() {
        this(new ImageRenditions());
    }

    @Autowired
    public ProductImageProcessor(ImageRenditions renditions) {
        this.renditions = renditions;
    }

    public Prepared prepare(FileUpload upload) {
        try {
            int limit = Math.toIntExact(FileCategory.PRODUCT_IMAGE.maxBytes());
            ContentTypePolicy.check(FileCategory.PRODUCT_IMAGE, upload.contentType(), upload.sizeBytes(), new byte[0]);
            byte[] bytes = upload.content().readNBytes(limit + 1);
            ContentTypePolicy.check(FileCategory.PRODUCT_IMAGE, upload.contentType(), bytes.length,
                    Arrays.copyOf(bytes, Math.min(16, bytes.length)));
            if (bytes.length != upload.sizeBytes()) {
                throw new BusinessException(ErrorCode.VALIDATION_FAILED, "Declared file size differs from received bytes");
            }
            List<Rendered> rendered = renditions.render(bytes, false, EDGES).stream()
                    .map(r -> new Rendered(r.edge(), r.width(), r.height(), r.contentType(), r.bytes())).toList();
            return new Prepared(bytes, rendered);
        } catch (IOException ex) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "Image is damaged or unreadable");
        }
    }

    /** Smallest step that brings the decode under budget while keeping the largest rendition sharp. */
    static int subsamplingStep(int width, int height) {
        return ImageRenditions.subsamplingStep(width, height, LARGEST_RENDITION_EDGE);
    }

    static BufferedImage orient(BufferedImage source, int orientation) {
        return ImageRenditions.orient(source, orientation);
    }
}
