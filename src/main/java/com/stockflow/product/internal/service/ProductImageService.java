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
import com.stockflow.product.api.ProductStatus;
import com.stockflow.product.internal.domain.ImageRendition;
import com.stockflow.product.internal.domain.Product;
import com.stockflow.product.internal.domain.ProductChangeType;
import com.stockflow.product.internal.repository.ProductChangeLogRepository;
import com.stockflow.product.internal.repository.ProductImageRepository;
import com.stockflow.product.internal.repository.ProductImageRepository.ProductImage;
import com.stockflow.product.internal.repository.ProductJpaRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.io.ByteArrayInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * The product's own image (ADR-0012): one per product, the product's face on the storefront's lists and category
 * pages. Uploading replaces the image the product had; deleting leaves it with none, and its cards fall back to the
 * variants' lead image. The product page is not this image's business: it leads with the variants' own images
 * ({@link ProductMediaService}, many per variant).
 *
 * <p><b>No publish step.</b> Unlike a variant's image, which is published by hand, this one shows wherever the
 * product shows, i.e. once the product is published (owner decision 2026-11-27). Its renditions are copied to the CDN prefix
 * by whichever comes second - the upload of a published product's image, here, or the product's publication
 * ({@code ProductPublicationAdapter.publish}) - so a draft's image is never public.</p>
 *
 * <p>The upload follows {@link ProductMediaService#upload}: decoding, resizing, the virus scan and the object-store
 * writes run with no transaction open, and the database is touched only to fail fast and, under the product row
 * lock, to attach the stored objects. The replaced image's private objects are deleted once that commits; its
 * published copies stay, as a deleted variant image's do, because a CDN may still serve them for its cache
 * lifetime.</p>
 */
@Service
@Transactional
public class ProductImageService {

    private static final Logger log = LoggerFactory.getLogger(ProductImageService.class);

    private static final int MAX_ALT_TEXT = 255;

    private final ProductVariantService variants;
    private final ProductJpaRepository productRows;
    private final ProductImageRepository images;
    private final FileTransfers files;
    private final FileStorage storage;
    private final ProductImageProcessor processor;
    private final UploadInspection inspection;
    private final TransactionOperations tx;
    private final ProductChangeLogRepository history;

    public ProductImageService(ProductVariantService variants, ProductJpaRepository productRows,
                               ProductImageRepository images, FileTransfers files, FileStorage storage,
                               ProductImageProcessor processor, UploadInspection inspection, TransactionOperations tx,
                               ProductChangeLogRepository history) {
        this.variants = variants;
        this.productRows = productRows;
        this.images = images;
        this.files = files;
        this.storage = storage;
        this.processor = processor;
        this.inspection = inspection;
        this.tx = tx;
        this.history = history;
    }

    /**
     * The product's image as the back office shows it.
     *
     * @param renditionUrls edge to a short-lived link showing that rendition; empty where none could be signed
     */
    public record ProductImageView(UUID productId, StoredFile original, List<ImageRendition> renditions,
                                   String altText, Map<Integer, String> renditionUrls) {
        public ProductImageView {
            renditions = List.copyOf(renditions);
            renditionUrls = renditionUrls == null ? Map.of() : Map.copyOf(renditionUrls);
        }
    }

    // ------------------------------------------------------------------ upload

    /**
     * Puts {@code upload} on the product as its image, in place of the one it had. Not transactional, for the reason
     * {@link ProductMediaService#upload} gives.
     */
    @Auditable(action = AuditAction.UPDATE, resourceType = "product-image", resourceId = "#productId")
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public ProductImageView upload(UUID productId, FileUpload upload, String altText, UUID actor) {
        if (actor == null) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED);
        }
        String alt = altText(altText);
        tx.executeWithoutResult(status -> variants.requireChangeable(productId));

        var prepared = processor.prepare(upload);
        inspection.requireClean(new ByteArrayInputStream(prepared.original()));

        var written = new ArrayList<String>();
        try {
            var original = store(FileCategory.PRODUCT_IMAGE_ORIGINAL, upload.originalName(), upload.contentType(),
                    prepared.original(), written);
            var renditions = new ArrayList<ImageRendition>();
            for (var rendered : prepared.renditions()) {
                var file = store(FileCategory.PRODUCT_RENDITION, "display-" + rendered.edge()
                                + (rendered.contentType().equals("image/png") ? ".png" : ".jpg"),
                        rendered.contentType(), rendered.bytes(), written);
                renditions.add(new ImageRendition(rendered.edge(), rendered.width(), rendered.height(), file));
            }
            var image = new ProductImage(original, sha256(prepared.original()), renditions, alt);
            return attach(productId, image);
        } catch (RuntimeException failure) {
            if (!(failure instanceof CommitOutcomeUnknown)) {
                discard(written);
            }
            throw failure instanceof CommitOutcomeUnknown unknown ? unknown.cause() : failure;
        }
    }

    /** The write: under the product lock, re-checks the product still takes changes, then swaps the image. */
    private ProductImageView attach(UUID productId, ProductImage image) {
        boolean[] rolledBack = {false};
        try {
            return tx.execute(status -> {
                try {
                    Product product = variants.requireChangeable(productId);
                    Optional<ProductImage> previous = images.find(productId);
                    images.set(productId, image);
                    if (product.status() == ProductStatus.PUBLISHED) {
                        image.renditions().forEach(rendition -> files.publish(rendition.file().key()));
                    }
                    history.record(productId, ProductChangeType.UPDATED, null, null, null, null, List.of("image"));
                    previous.ifPresent(old -> deleteAfterCommit(privateKeysOf(old)));
                    return view(productId, image, Map.of());
                } catch (RuntimeException ex) {
                    rolledBack[0] = true;
                    throw ex;
                }
            });
        } catch (RuntimeException ex) {
            // A failure inside the callback rolled back, so nothing references the objects. A failure outside it
            // happened while committing: the row may name them, so they are kept.
            throw rolledBack[0] ? ex : new CommitOutcomeUnknown(ex);
        }
    }

    // ------------------------------------------------------------------ reads and edits

    /** The product's image with a link showing each rendition, or empty when it has none. */
    @Transactional(readOnly = true)
    public Optional<ProductImageView> get(UUID productId) {
        requireProduct(productId);
        return images.find(productId).map(image -> {
            var urls = new HashMap<Integer, String>();
            image.renditions().forEach(r -> files.tryViewLink(r.file().key())
                    .ifPresent(link -> urls.put(r.edge(), link.url())));
            return view(productId, image, urls);
        });
    }

    /** Changes the alt text of the product's image; {@code MEDIA_NOT_FOUND} when it has none. */
    @Auditable(action = AuditAction.UPDATE, resourceType = "product-image", resourceId = "#productId")
    public ProductImageView describe(UUID productId, String altText) {
        String alt = altText(altText);
        variants.requireChangeable(productId);
        var image = images.find(productId).orElseThrow(() -> noImage(productId));
        if (java.util.Objects.equals(alt, image.altText())) {
            return view(productId, image, Map.of());
        }
        var described = new ProductImage(image.original(), image.checksum(), image.renditions(), alt);
        images.set(productId, described);
        history.record(productId, ProductChangeType.UPDATED, null, null, null, null, List.of("imageAltText"));
        return view(productId, described, Map.of());
    }

    /**
     * Takes the image off the product, whose cards then show its variants' lead image. A product without an image is
     * left as it is: the outcome asked for already holds.
     */
    @Auditable(action = AuditAction.DELETE, resourceType = "product-image", resourceId = "#productId")
    public void delete(UUID productId) {
        variants.requireChangeable(productId);
        var previous = images.find(productId);
        if (previous.isEmpty()) {
            return;
        }
        images.clear(productId);
        history.record(productId, ProductChangeType.UPDATED, null, null, null, null, List.of("image"));
        deleteAfterCommit(privateKeysOf(previous.get()));
    }

    // ------------------------------------------------------------------ the product's lifecycle

    /**
     * Copies the product's image renditions to the CDN prefix, inside the caller's transaction (the product's
     * publication): {@link FileTransfers#publish} deletes a copy it made again if that rolls back.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void publishFor(UUID productId) {
        images.find(productId).ifPresent(image ->
                image.renditions().forEach(rendition -> files.publish(rendition.file().key())));
    }

    /** Every object-store key of the product's image, original and renditions, for deleting the product. */
    @Transactional(readOnly = true)
    public List<String> storedKeysOf(UUID productId) {
        return images.find(productId).map(ProductImageService::privateKeysOf).orElse(List.of());
    }

    // ------------------------------------------------------------------ helpers

    private void requireProduct(UUID productId) {
        if (!productRows.existsById(productId)) {
            throw new BusinessException(ErrorCode.PRODUCT_NOT_FOUND, "No product with id " + productId);
        }
    }

    private static String altText(String altText) {
        if (altText == null || altText.isBlank()) {
            return null;
        }
        String alt = altText.strip();
        if (alt.length() > MAX_ALT_TEXT) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "altText must be at most %d characters".formatted(MAX_ALT_TEXT));
        }
        return alt;
    }

    private static BusinessException noImage(UUID productId) {
        return new BusinessException(ErrorCode.MEDIA_NOT_FOUND, "Product %s has no image".formatted(productId));
    }

    private static List<String> privateKeysOf(ProductImage image) {
        var keys = new ArrayList<String>();
        keys.add(image.original().key());
        image.renditions().forEach(rendition -> keys.add(rendition.file().key()));
        return keys;
    }

    private static ProductImageView view(UUID productId, ProductImage image, Map<Integer, String> urls) {
        return new ProductImageView(productId, image.original(), image.renditions(), image.altText(), urls);
    }

    private StoredFile store(FileCategory category, String name, String contentType, byte[] bytes,
                             List<String> written) {
        StoredFile file = storage.store(category, name, contentType, bytes.length, new ByteArrayInputStream(bytes),
                sha256(bytes));
        written.add(file.key());
        return file;
    }

    /** Deletes {@code keys} once the surrounding transaction commits; a rollback keeps them, still referenced. */
    private void deleteAfterCommit(List<String> keys) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            discard(keys);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                discard(keys);
            }
        });
    }

    private void discard(List<String> keys) {
        for (String key : keys) {
            try {
                storage.delete(key);
            } catch (RuntimeException cleanupFailure) {
                log.error("Orphaned product image object requires cleanup: {}", key, cleanupFailure);
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

    /** Carries a failure that happened while committing, where the outcome is unknown. */
    private static final class CommitOutcomeUnknown extends RuntimeException {
        CommitOutcomeUnknown(RuntimeException cause) {
            super(cause);
        }

        RuntimeException cause() {
            return (RuntimeException) getCause();
        }
    }
}
