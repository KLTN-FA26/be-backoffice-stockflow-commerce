package com.stockflow.product.internal.service;

import com.stockflow.common.audit.AuditAction;
import com.stockflow.common.audit.Auditable;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.id.Identifiers;
import com.stockflow.common.idempotency.IdempotencyKeys;
import com.stockflow.common.storage.DownloadLink;
import com.stockflow.common.storage.FileCategory;
import com.stockflow.common.storage.FileTransfers;
import com.stockflow.common.storage.FileStorage;
import com.stockflow.common.storage.FileUpload;
import com.stockflow.common.storage.StoredFile;
import com.stockflow.common.storage.UploadInspection;
import com.stockflow.product.api.ProductStatus;
import com.stockflow.product.internal.domain.ImageRendition;
import com.stockflow.product.internal.domain.Product;
import com.stockflow.product.internal.domain.ProductId;
import com.stockflow.product.internal.domain.ProductImage;
import com.stockflow.product.internal.domain.ProductRepository;
import java.io.ByteArrayInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionOperations;
/** Module-local use case: HTTP uploads do not enlarge the cross-module product contract. */
@Service
@Transactional
public class ProductImageService {
    private static final Logger log = LoggerFactory.getLogger(ProductImageService.class);
    private final ProductRepository products;
    private final FileTransfers files;
    private final FileStorage storage;
    private final ProductImageProcessor processor;
    private final UploadInspection inspection;
    private final TransactionOperations tx;

    public ProductImageService(ProductRepository products, FileTransfers files, FileStorage storage,
                               ProductImageProcessor processor, UploadInspection inspection,
                               TransactionOperations tx) {
        this.products = products;
        this.files = files;
        this.storage = storage;
        this.processor = processor;
        this.inspection = inspection;
        this.tx = tx;
    }

    /**
     * Not transactional on purpose. Decoding, resizing, the virus scan and the object-store writes
     * take seconds; holding the product row lock and a pooled connection across them would stall
     * every other writer of this product and, with ClamAV slow or down, drain the pool. The
     * database is touched in two short transactions instead: a read to fail fast, and a locked
     * write that only attaches the already-stored objects.
     */
    @Auditable(action = AuditAction.UPDATE, resourceType = "product", resourceId = "#productId")
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public ProductImage upload(UUID productId, FileUpload upload) {
        return upload(productId, upload, null, null);
    }

    @Auditable(action = AuditAction.UPDATE, resourceType = "product", resourceId = "#productId")
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public ProductImage upload(UUID productId, FileUpload upload, UUID actor, String requestKey) {
        if (requestKey != null && actor == null) { throw new BusinessException(ErrorCode.UNAUTHORIZED); }
        String key = requestKey == null ? null : actor + ":" + IdempotencyKeys.validate(requestKey);

        tx.executeWithoutResult(status -> {
            Product product = products.findById(new ProductId(productId))
                    .orElseThrow(() -> new BusinessException(ErrorCode.PRODUCT_NOT_FOUND));
            // A keyed request may be a replay of an upload that already succeeded, which must keep
            // answering even after the product moved on; it is judged once the checksum is known.
            if (key == null) { requireAcceptsImages(product); }
        });

        var prepared = processor.prepare(upload);
        String checksum = sha256(prepared.original());

        ProductImage replay = tx.execute(status -> {
            Product product = products.findById(new ProductId(productId))
                    .orElseThrow(() -> new BusinessException(ErrorCode.PRODUCT_NOT_FOUND));
            ProductImage existing = replayOf(product, key, upload, checksum);
            if (existing == null) { requireAcceptsImages(product); }
            return existing;
        });
        if (replay != null) { return replay; }

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
            return attach(productId, upload, key, checksum, stored, renditions, written);
        } catch (RuntimeException failure) {
            if (!(failure instanceof CommitOutcomeUnknown)) { discard(written); }
            throw failure instanceof CommitOutcomeUnknown unknown ? unknown.cause() : failure;
        }
    }

    /** The write: re-checks under the row lock what the read could only guess, then attaches. */
    private ProductImage attach(UUID productId, FileUpload upload, String key, String checksum,
                                StoredFile stored, List<ImageRendition> renditions, List<String> written) {
        boolean[] rolledBack = {false};
        ProductImage result;
        try {
            result = tx.execute(status -> {
                try {
                    Product product = products.findForUpdate(new ProductId(productId))
                            .orElseThrow(() -> new BusinessException(ErrorCode.PRODUCT_NOT_FOUND));
                    ProductImage existing = replayOf(product, key, upload, checksum);
                    if (existing != null) { return existing; }
                    requireAcceptsImages(product);
                    int order = product.images().stream().mapToInt(ProductImage::sortOrder).max().orElse(-1) + 1;
                    var image = new ProductImage(Identifiers.newId(), null, order, stored, renditions, key, checksum);
                    product.addImage(image);
                    products.save(product);
                    return image;
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
        if (result.storedFile() != stored) { discard(written); }
        return result;
    }

    private ProductImage replayOf(Product product, String key, FileUpload upload, String checksum) {
        if (key == null) { return null; }
        var replay = product.images().stream().filter(i -> key.equals(i.uploadKey())).findFirst();
        if (replay.isEmpty()) { return null; }
        var image = replay.get();
        if (!checksum.equals(image.checksum()) || !upload.originalName().equals(image.storedFile().originalName())
                || !upload.contentType().split(";", 2)[0].trim().equalsIgnoreCase(image.storedFile().contentType())) {
            throw new BusinessException(ErrorCode.IDEMPOTENCY_KEY_REUSED);
        }
        return image;
    }

    private static void requireAcceptsImages(Product product) {
        if (product.status() == ProductStatus.DISCONTINUED || product.status() == ProductStatus.PENDING_APPROVAL) {
            throw new BusinessException(ErrorCode.INVALID_PRODUCT_STATUS_TRANSITION,
                    "This product does not currently accept image changes");
        }
    }

    private StoredFile store(FileCategory category, String name, String contentType, byte[] bytes, List<String> written) {
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
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }

    /** Carries a failure that happened while committing, where the outcome is unknown. */
    private static final class CommitOutcomeUnknown extends RuntimeException {
        CommitOutcomeUnknown(RuntimeException cause) { super(cause); }
        RuntimeException cause() { return (RuntimeException) getCause(); }
    }

    @Transactional(readOnly = true)
    public List<ProductImage> list(UUID productId) {
        return requireProduct(productId).images().stream()
                .sorted(Comparator.comparingInt(ProductImage::sortOrder)).toList();
    }

    @Transactional(readOnly = true)
    public DownloadLink downloadLink(UUID productId, UUID imageId) {
        var image = requireProduct(productId).images().stream()
                .filter(candidate -> candidate.id().equals(imageId)).findFirst()
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        if (image.storedFile() == null) {
            throw new BusinessException(ErrorCode.CONFLICT, "Legacy images already have an external URL");
        }
        return files.downloadLink(image.storedFile().key());
    }

    private Product requireProduct(UUID id) {
        return products.findById(new ProductId(id))
                .orElseThrow(() -> new BusinessException(ErrorCode.PRODUCT_NOT_FOUND));
    }
}
