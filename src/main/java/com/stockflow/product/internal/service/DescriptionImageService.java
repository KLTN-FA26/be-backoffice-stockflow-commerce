package com.stockflow.product.internal.service;

import com.stockflow.common.audit.AuditAction;
import com.stockflow.common.audit.Auditable;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.storage.FileCategory;
import com.stockflow.common.storage.FileStorage;
import com.stockflow.common.storage.FileTransfers;
import com.stockflow.common.storage.FileUpload;
import com.stockflow.common.storage.StoredFile;
import com.stockflow.common.storage.UploadInspection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionOperations;

import java.io.ByteArrayInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

/**
 * An image put inside a product's or a variant's description (ADR-0013): the editor uploads it, gets back a public URL
 * and writes it into an {@code <img>}. The only images {@link RichTextPolicy} lets a description show are these.
 *
 * <p><b>Public at once.</b> The editor needs a URL it can show while the description is still being written, and a
 * description has no publish step of its own, so the renditions are copied to the CDN prefix as soon as they are
 * stored. Only the display renditions are kept: they are re-encoded, so the photograph's metadata (its location, its
 * camera) never reaches a customer, and there is no private original to keep track of.</p>
 *
 * <p><b>No row.</b> The image belongs to the HTML that names it; nothing in the database lists these images. The
 * renditions' category is not swept as orphaned ({@code OrphanedFileSweeper} sweeps only what a module claims), so
 * an image a description no longer names stays on the CDN, like a replaced gallery image's published copy.</p>
 *
 * <p>The upload follows {@link ProductMediaService#upload}: decoding, resizing, the virus scan and the object-store
 * writes run with no transaction open.</p>
 */
@Service
public class DescriptionImageService {

    private static final Logger log = LoggerFactory.getLogger(DescriptionImageService.class);

    private final ProductVariantService variants;
    private final ProductImageProcessor processor;
    private final UploadInspection inspection;
    private final FileStorage storage;
    private final FileTransfers files;
    private final ProductMediaProperties delivery;
    private final TransactionOperations tx;

    public DescriptionImageService(ProductVariantService variants, ProductImageProcessor processor,
                                   UploadInspection inspection, FileStorage storage, FileTransfers files,
                                   ProductMediaProperties delivery, TransactionOperations tx) {
        this.variants = variants;
        this.processor = processor;
        this.inspection = inspection;
        this.storage = storage;
        this.files = files;
        this.delivery = delivery;
        this.tx = tx;
    }

    /** One display size of the image, at its public URL. */
    public record Rendition(int edge, int width, int height, String url) {
    }

    /** The uploaded image: {@code url} is its largest rendition, the one to put in the {@code <img>}. */
    public record DescriptionImage(String url, int width, int height, List<Rendition> renditions) {
        public DescriptionImage {
            renditions = List.copyOf(renditions);
        }
    }

    @Auditable(action = AuditAction.UPDATE, resourceType = "product-description-image", resourceId = "#productId")
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public DescriptionImage upload(UUID productId, FileUpload upload, UUID actor) {
        if (actor == null) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED);
        }
        tx.executeWithoutResult(status -> variants.requireChangeable(productId));

        var prepared = processor.prepare(upload);
        inspection.requireClean(new ByteArrayInputStream(prepared.original()));

        var written = new ArrayList<String>();
        try {
            var stored = new ArrayList<StoredRendition>();
            for (var rendered : prepared.renditions()) {
                StoredFile file = storage.store(FileCategory.PRODUCT_RENDITION, "description-" + rendered.edge()
                                + (rendered.contentType().equals("image/png") ? ".png" : ".jpg"),
                        rendered.contentType(), rendered.bytes().length, new ByteArrayInputStream(rendered.bytes()),
                        sha256(rendered.bytes()));
                written.add(file.key());
                stored.add(new StoredRendition(rendered.edge(), rendered.width(), rendered.height(), file.key()));
            }
            // FileTransfers.publish needs a transaction: it deletes a copy it made again if that rolls back.
            List<Rendition> renditions = tx.execute(status -> {
                variants.requireChangeable(productId);
                return stored.stream().map(r -> new Rendition(r.edge(), r.width(), r.height(),
                        delivery.publicUrl(files.publish(r.key())))).toList();
            });
            Rendition largest = renditions.stream().max(Comparator.comparingInt(Rendition::edge))
                    .orElseThrow(() -> new BusinessException(ErrorCode.UNSUPPORTED_MEDIA_TYPE));
            return new DescriptionImage(largest.url(), largest.width(), largest.height(), renditions);
        } catch (RuntimeException failure) {
            discard(written);
            throw failure;
        }
    }

    private record StoredRendition(int edge, int width, int height, String key) {
    }

    private void discard(List<String> keys) {
        for (String key : keys) {
            try {
                storage.delete(key);
            } catch (RuntimeException cleanupFailure) {
                log.error("Orphaned description image requires cleanup: {}", key, cleanupFailure);
            }
        }
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
