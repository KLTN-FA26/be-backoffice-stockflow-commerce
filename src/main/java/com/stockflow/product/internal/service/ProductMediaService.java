package com.stockflow.product.internal.service;

import com.stockflow.common.audit.AuditAction;
import com.stockflow.common.audit.Auditable;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.id.Identifiers;
import com.stockflow.common.idempotency.IdempotencyKeys;
import com.stockflow.common.storage.DownloadLink;
import com.stockflow.common.storage.FileCategory;
import com.stockflow.common.storage.FileStorage;
import com.stockflow.common.storage.FileTransfers;
import com.stockflow.common.storage.FileUpload;
import com.stockflow.common.storage.StorageKeys;
import com.stockflow.common.storage.StoredFile;
import com.stockflow.common.storage.UploadInspection;
import com.stockflow.identity.api.IdentityService;
import com.stockflow.product.api.ProductStatus;
import com.stockflow.product.internal.domain.ImageRendition;
import com.stockflow.product.internal.domain.Product;
import com.stockflow.product.internal.domain.VariantStatus;
import com.stockflow.product.internal.entity.MediaJpaEntity;
import com.stockflow.product.internal.entity.ProductJpaEntity;
import com.stockflow.product.internal.entity.VariantJpaEntity;
import com.stockflow.product.internal.repository.MediaJpaRepository;
import com.stockflow.product.internal.repository.ProductJpaRepository;
import com.stockflow.product.internal.repository.VariantJpaRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionOperations;

import java.io.ByteArrayInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.UUID;

/**
 * The images of a variant (decision D5, table {@code product.media}): upload, describe, order,
 * publish to the storefront, withdraw, delete.
 *
 * <p><b>Four-eyes.</b> An image reaches the storefront only when someone other than its uploader
 * publishes it — the rule the old gallery approval enforced, now per image. Publishing copies the
 * image's renditions to the CDN-served prefix inside the publishing transaction
 * ({@link FileTransfers#publish}), so an image is public exactly when its row says so.</p>
 *
 * <p>An image is a module-local use case: uploads do not enlarge the cross-module product
 * contract. Catalog reads the published images through {@code ProductPublication}.</p>
 */
@Service
@Transactional
public class ProductMediaService {

    /** A variant's gallery, as the storefront shows it. */
    static final int MAX_IMAGES_PER_VARIANT = 20;

    private static final Logger log = LoggerFactory.getLogger(ProductMediaService.class);

    private final ProductVariantService variants;
    private final VariantJpaRepository variantRows;
    private final ProductJpaRepository productRows;
    private final MediaJpaRepository media;
    private final FileTransfers files;
    private final FileStorage storage;
    private final ProductImageProcessor processor;
    private final UploadInspection inspection;
    private final TransactionOperations tx;
    private final IdentityService identity;
    private final ProductMediaProperties delivery;
    private final Clock clock;

    public ProductMediaService(ProductVariantService variants, VariantJpaRepository variantRows,
                               ProductJpaRepository productRows, MediaJpaRepository media, FileTransfers files,
                               FileStorage storage, ProductImageProcessor processor, UploadInspection inspection,
                               TransactionOperations tx, IdentityService identity, ProductMediaProperties delivery,
                               Clock clock) {
        this.variants = variants;
        this.variantRows = variantRows;
        this.productRows = productRows;
        this.media = media;
        this.files = files;
        this.storage = storage;
        this.processor = processor;
        this.inspection = inspection;
        this.tx = tx;
        this.identity = identity;
        this.delivery = delivery;
        this.clock = clock;
    }

    public record MediaView(UUID id, UUID variantId, String kind, String url, StoredFile original,
                            List<ImageRendition> renditions, String altText, int sortOrder, boolean primary,
                            boolean published, Instant publishedAt, UUID publishedBy, String uploadedBy,
                            Instant uploadedAt, long version) {
    }

    public record PublicRendition(int edge, int width, int height, String contentType, String url) {
    }

    public record PublicImage(UUID imageId, String altText, boolean primary, String url,
                              List<PublicRendition> renditions) {
    }

    public record PublicGallery(UUID productId, UUID variantId, String sku, List<PublicImage> images) {
    }

    // ------------------------------------------------------------------ upload

    /**
     * Not transactional on purpose. Decoding, resizing, the virus scan and the object-store writes
     * take seconds; holding the product row lock and a pooled connection across them would stall
     * every other writer of this product and, with ClamAV slow or down, drain the pool. The
     * database is touched in short transactions instead: a read to fail fast, a replay check once the
     * checksum is known, and a locked write that only attaches the already-stored objects.
     *
     * <p>With an {@code Idempotency-Key}, a repeat of an upload that already succeeded answers with
     * the image it created, even after the product moved on; the same key with a different file is
     * {@code IDEMPOTENCY_KEY_REUSED}.</p>
     */
    @Auditable(action = AuditAction.UPDATE, resourceType = "product-media", resourceId = "#variantId")
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public MediaView upload(UUID productId, UUID variantId, FileUpload upload, UUID actor, String requestKey) {
        if (actor == null) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED);
        }
        String key = requestKey == null ? null : actor + ":" + IdempotencyKeys.validate(requestKey);

        tx.executeWithoutResult(status -> {
            variants.requireVariant(productId, variantId);
            if (key == null) {
                requireAcceptsImages(productId, variantId);
            }
        });

        var prepared = processor.prepare(upload);
        String checksum = sha256(prepared.original());

        MediaView replay = tx.execute(status -> {
            MediaView existing = replayOf(variantId, key, upload, checksum);
            if (existing == null) {
                requireAcceptsImages(productId, variantId);
            }
            return existing;
        });
        if (replay != null) {
            return replay;
        }

        inspection.requireClean(new ByteArrayInputStream(prepared.original()));

        var written = new ArrayList<String>();
        try {
            var stored = store(FileCategory.PRODUCT_IMAGE_ORIGINAL, upload.originalName(), upload.contentType(),
                    prepared.original(), written);
            var renditions = new ArrayList<ImageRendition>();
            for (var rendered : prepared.renditions()) {
                var file = store(FileCategory.PRODUCT_RENDITION, "display-" + rendered.edge()
                                + (rendered.contentType().equals("image/png") ? ".png" : ".jpg"),
                        rendered.contentType(), rendered.bytes(), written);
                renditions.add(new ImageRendition(rendered.edge(), rendered.width(), rendered.height(), file));
            }
            return attach(productId, variantId, upload, key, checksum, stored, renditions, written);
        } catch (RuntimeException failure) {
            if (!(failure instanceof CommitOutcomeUnknown)) {
                discard(written);
            }
            throw failure instanceof CommitOutcomeUnknown unknown ? unknown.cause() : failure;
        }
    }

    /** The write: re-checks under the product lock what the reads could only guess, then attaches. */
    private MediaView attach(UUID productId, UUID variantId, FileUpload upload, String key, String checksum,
                             StoredFile stored, List<ImageRendition> renditions, List<String> written) {
        boolean[] rolledBack = {false};
        MediaView result;
        try {
            result = tx.execute(status -> {
                try {
                    variants.requireChangeable(productId);
                    MediaView existing = replayOf(variantId, key, upload, checksum);
                    if (existing != null) {
                        return existing;
                    }
                    requireAcceptsImages(productId, variantId);
                    var image = new MediaJpaEntity(Identifiers.newId(), variantId, stored, renditions, key, checksum,
                            media.nextSortOrder(variantId));
                    image.setPrimary(media.findByVariantIdOrderBySortOrderAscIdAsc(variantId).stream()
                            .noneMatch(MediaJpaEntity::isPrimary));
                    return view(media.saveAndFlush(image));
                } catch (RuntimeException ex) {
                    rolledBack[0] = true;
                    throw ex;
                }
            });
        } catch (RuntimeException ex) {
            // A failure inside the callback rolled back, so nothing references the objects. A failure
            // outside it happened while committing: the row may exist, so the objects are kept.
            throw rolledBack[0] ? ex : new CommitOutcomeUnknown(ex);
        }
        if (result.original() == null || !result.original().key().equals(stored.key())) {
            discard(written);
        }
        return result;
    }

    private MediaView replayOf(UUID variantId, String key, FileUpload upload, String checksum) {
        if (key == null) {
            return null;
        }
        var replay = media.findByUploadKey(key);
        if (replay.isEmpty()) {
            return null;
        }
        var image = replay.get();
        var original = image.storedFile();
        if (!image.getVariantId().equals(variantId) || !checksum.equals(image.getChecksum())
                || !upload.originalName().equals(original.originalName())
                || !upload.contentType().split(";", 2)[0].trim().equalsIgnoreCase(original.contentType())) {
            throw new BusinessException(ErrorCode.IDEMPOTENCY_KEY_REUSED);
        }
        return view(image);
    }

    private void requireAcceptsImages(UUID productId, UUID variantId) {
        variants.requireChangeable(productId);
        if (variants.requireVariant(productId, variantId).getStatus() == VariantStatus.OBSOLETE) {
            throw new BusinessException(ErrorCode.INVALID_VARIANT_TRANSITION, "An obsolete variant takes no new images");
        }
        if (media.countByVariantId(variantId) >= MAX_IMAGES_PER_VARIANT) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "A variant holds at most %d images".formatted(MAX_IMAGES_PER_VARIANT));
        }
    }

    // ------------------------------------------------------------------ reads

    @Transactional(readOnly = true)
    public List<MediaView> list(UUID productId, UUID variantId) {
        variants.requireVariant(productId, variantId);
        return media.findByVariantIdOrderBySortOrderAscIdAsc(variantId).stream().map(ProductMediaService::view).toList();
    }

    @Transactional(readOnly = true)
    public MediaView get(UUID productId, UUID variantId, UUID mediaId) {
        return view(requireMedia(productId, variantId, mediaId));
    }

    /** A short-lived link to the private original. Only for a stored file: a URL image has its URL. */
    @Transactional(readOnly = true)
    public DownloadLink downloadLink(UUID productId, UUID variantId, UUID mediaId) {
        var image = requireMedia(productId, variantId, mediaId);
        if (image.storedFile() == null) {
            throw new BusinessException(ErrorCode.CONFLICT, "This image is an external URL, not a stored file");
        }
        return files.downloadLink(image.getStorageKey());
    }

    @Transactional(readOnly = true)
    public DownloadLink renditionLink(UUID productId, UUID variantId, UUID mediaId, int edge) {
        var rendition = requireMedia(productId, variantId, mediaId).getRenditions().stream()
                .filter(r -> r.edge() == edge).findFirst()
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "No rendition of edge " + edge));
        return files.downloadLink(rendition.file().key());
    }

    // ------------------------------------------------------------------ edits

    /** Alt text, position, cover. Making one image the cover takes it away from the previous one. */
    @Auditable(action = AuditAction.UPDATE, resourceType = "product-media", resourceId = "#mediaId")
    public MediaView describe(UUID productId, UUID variantId, UUID mediaId, String altText, Integer sortOrder,
                              boolean primary) {
        variants.requireChangeable(productId);
        var image = requireMedia(productId, variantId, mediaId);
        if (altText != null && altText.length() > 255) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "altText must be at most 255 characters");
        }
        image.describe(altText, sortOrder == null ? image.getSortOrder() : sortOrder);
        if (primary && !image.isPrimary()) {
            // uk_media_primary is a partial unique index: the old cover must be cleared and flushed first.
            media.findByVariantIdOrderBySortOrderAscIdAsc(variantId).stream()
                    .filter(MediaJpaEntity::isPrimary).forEach(other -> other.setPrimary(false));
            media.flush();
            image.setPrimary(true);
        }
        return view(media.saveAndFlush(image));
    }

    /**
     * Removes the image. Its stored objects are not deleted: a published copy may still be cached by
     * the CDN for the storefront's cache lifetime, and nothing references the objects any more, so an
     * object-store lifecycle rule is the right tool to collect them.
     */
    @Auditable(action = AuditAction.DELETE, resourceType = "product-media", resourceId = "#mediaId")
    public void delete(UUID productId, UUID variantId, UUID mediaId) {
        variants.requireChangeable(productId);
        var image = requireMedia(productId, variantId, mediaId);
        boolean wasPrimary = image.isPrimary();
        media.delete(image);
        media.flush();
        if (wasPrimary) {
            media.findByVariantIdOrderBySortOrderAscIdAsc(variantId).stream().findFirst()
                    .ifPresent(next -> next.setPrimary(true));
        }
    }

    // ------------------------------------------------------------------ publication

    /**
     * Puts images on the storefront. Four-eyes: the publisher must not be who uploaded any of them;
     * the product must be approved; each image must be a stored file with display renditions. The
     * renditions are copied to the CDN prefix in this transaction.
     */
    @Auditable(action = AuditAction.APPROVE, resourceType = "product-media", resourceId = "#variantId")
    public List<MediaView> publish(UUID productId, UUID variantId, List<UUID> mediaIds, UUID actor) {
        if (actor == null) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED);
        }
        Product product = variants.requireChangeable(productId);
        if (product.status() != ProductStatus.APPROVED && product.status() != ProductStatus.PUBLISHED) {
            throw new BusinessException(ErrorCode.PRODUCT_NOT_APPROVED, "Approve the product before publishing its images");
        }
        if (mediaIds == null || mediaIds.isEmpty()) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "Name at least one image to publish");
        }
        String actorName = identity.profile(actor).username();
        Instant now = clock.instant();
        var result = new ArrayList<MediaView>();
        for (UUID mediaId : new LinkedHashSet<>(mediaIds)) {
            var image = requireMedia(productId, variantId, mediaId);
            String uploader = image.getCreatedBy();
            if (actor.toString().equals(uploader) || actorName.equals(uploader)) {
                throw new BusinessException(ErrorCode.SELF_APPROVAL_NOT_ALLOWED,
                        "An image is published by someone other than who uploaded it");
            }
            if (image.storedFile() == null || image.getRenditions().isEmpty()) {
                throw new BusinessException(ErrorCode.CONFLICT,
                        "Only an uploaded image with display renditions can be published; re-upload " + mediaId);
            }
            image.getRenditions().forEach(rendition -> files.publish(rendition.file().key()));
            if (!image.isPublished()) {
                image.publish(actor, now);
            }
            result.add(view(media.saveAndFlush(image)));
        }
        return result;
    }

    /** Off the storefront. A product published with no image left is not unpublished by this. */
    @Auditable(action = AuditAction.UPDATE, resourceType = "product-media", resourceId = "#mediaId")
    public MediaView withdraw(UUID productId, UUID variantId, UUID mediaId) {
        variants.requireChangeable(productId);
        var image = requireMedia(productId, variantId, mediaId);
        image.withdraw();
        return view(media.saveAndFlush(image));
    }

    // ------------------------------------------------------------------ storefront

    /**
     * The anonymous storefront gallery: the published images of the variant named by {@code sku}, or
     * of the default variant. A variant with no published image of its own shows the default
     * variant's. Only for a published product and an active variant.
     */
    @Transactional(readOnly = true)
    public PublicGallery publicGallery(UUID productId, String sku) {
        ProductJpaEntity product = productRows.findById(productId)
                .filter(p -> p.getStatus() == ProductStatus.PUBLISHED)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        String wanted = sku == null ? "" : sku.strip();
        VariantJpaEntity defaultVariant = variantRows.findByProductIdAndDefaultVariantTrue(productId).orElse(null);
        VariantJpaEntity variant = wanted.isEmpty() ? defaultVariant
                : variantRows.findBySku(wanted).filter(v -> v.getProductId().equals(productId))
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        if (variant == null || variant.getStatus() != VariantStatus.ACTIVE) {
            throw new BusinessException(ErrorCode.NOT_FOUND);
        }
        List<PublicImage> images = publishedImages(variant.getId());
        if (images.isEmpty() && defaultVariant != null && !defaultVariant.getId().equals(variant.getId())) {
            images = publishedImages(defaultVariant.getId());
        }
        if (images.isEmpty()) {
            throw new BusinessException(ErrorCode.NOT_FOUND);
        }
        return new PublicGallery(product.getId(), variant.getId(), variant.getSku(), images);
    }

    private List<PublicImage> publishedImages(UUID variantId) {
        return media.findByVariantIdOrderBySortOrderAscIdAsc(variantId).stream()
                .filter(MediaJpaEntity::isPublished)
                .map(m -> new PublicImage(m.getId(), m.getAltText(), m.isPrimary(),
                        m.getStorageKey() == null ? m.getUrl() : null,
                        m.getRenditions().stream().map(r -> new PublicRendition(r.edge(), r.width(), r.height(),
                                r.file().contentType(),
                                delivery.publicUrl(StorageKeys.publishedKeyOf(r.file().key())))).toList()))
                .toList();
    }

    // ------------------------------------------------------------------ helpers

    private MediaJpaEntity requireMedia(UUID productId, UUID variantId, UUID mediaId) {
        variants.requireVariant(productId, variantId);
        return media.findByIdAndVariantId(mediaId, variantId)
                .orElseThrow(() -> new BusinessException(ErrorCode.MEDIA_NOT_FOUND,
                        "Variant %s has no image %s".formatted(variantId, mediaId)));
    }

    private StoredFile store(FileCategory category, String name, String contentType, byte[] bytes,
                             List<String> written) {
        StoredFile file = storage.store(category, name, contentType, bytes.length, new ByteArrayInputStream(bytes));
        written.add(file.key());
        return file;
    }

    private void discard(List<String> keys) {
        for (String key : keys) {
            try {
                storage.delete(key);
            } catch (RuntimeException cleanupFailure) {
                log.error("Orphaned upload requires cleanup: {}", key, cleanupFailure);
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

    static MediaView view(MediaJpaEntity m) {
        return new MediaView(m.getId(), m.getVariantId(), m.getKind(), m.getUrl(), m.storedFile(), m.getRenditions(),
                m.getAltText(), m.getSortOrder(), m.isPrimary(), m.isPublished(), m.getPublishedAt(),
                m.getPublishedBy(), m.getCreatedBy(), m.getCreatedAt(), m.getVersion());
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
