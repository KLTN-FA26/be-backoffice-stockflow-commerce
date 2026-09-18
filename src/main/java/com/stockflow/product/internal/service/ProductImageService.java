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
import com.stockflow.common.storage.FileUpload;
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
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
/** Module-local use case: HTTP uploads do not enlarge the cross-module product contract. */
@Service
@Transactional
public class ProductImageService {
    private final ProductRepository products;
    private final FileTransfers files;
    private final ProductImageProcessor processor;
    private final UploadInspection inspection;

    public ProductImageService(ProductRepository products, FileTransfers files, ProductImageProcessor processor,
                               UploadInspection inspection) {
        this.products = products;
        this.files = files;
        this.processor = processor;
        this.inspection = inspection;
    }

    @Auditable(action = AuditAction.UPDATE, resourceType = "product", resourceId = "#productId")
    public ProductImage upload(UUID productId, FileUpload upload) {
        return upload(productId, upload, null, null);
    }

    @Auditable(action = AuditAction.UPDATE, resourceType = "product", resourceId = "#productId")
    public ProductImage upload(UUID productId, FileUpload upload, UUID actor, String requestKey) {
        Product product = products.findForUpdate(new ProductId(productId))
                .orElseThrow(() -> new BusinessException(ErrorCode.PRODUCT_NOT_FOUND));
        if (requestKey != null && actor == null) { throw new BusinessException(ErrorCode.UNAUTHORIZED); }
        String key = requestKey == null ? null : actor + ":" + IdempotencyKeys.validate(requestKey);
        if (key == null && (product.status() == ProductStatus.DISCONTINUED || product.status() == ProductStatus.PENDING_APPROVAL)) {
            throw new BusinessException(ErrorCode.INVALID_PRODUCT_STATUS_TRANSITION);
        }
        var prepared = processor.prepare(upload);
        String checksum;
        try { checksum = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(prepared.original())); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
        if (key != null) {
            var replay = product.images().stream().filter(i -> key.equals(i.uploadKey())).findFirst();
            if (replay.isPresent()) {
                var image = replay.get();
                if (!checksum.equals(image.checksum()) || !upload.originalName().equals(image.storedFile().originalName())
                        || !upload.contentType().split(";", 2)[0].trim().equalsIgnoreCase(image.storedFile().contentType())) {
                    throw new BusinessException(ErrorCode.IDEMPOTENCY_KEY_REUSED);
                }
                return image;
            }
        }
        if (product.status() == ProductStatus.DISCONTINUED || product.status() == ProductStatus.PENDING_APPROVAL) {
            throw new BusinessException(ErrorCode.INVALID_PRODUCT_STATUS_TRANSITION,
                    "This product does not currently accept image changes");
        }
        inspection.requireClean(new ByteArrayInputStream(prepared.original()));
        var stored = files.store(FileCategory.PRODUCT_IMAGE, new FileUpload(upload.originalName(), upload.contentType(),
                prepared.original().length, new ByteArrayInputStream(prepared.original())));
        var renditions = new ArrayList<ImageRendition>();
        for (var rendered : prepared.renditions()) {
            var file = files.store(FileCategory.PRODUCT_IMAGE, new FileUpload("display-" + rendered.edge()
                    + (rendered.contentType().equals("image/png") ? ".png" : ".jpg"), rendered.contentType(),
                    rendered.bytes().length, new ByteArrayInputStream(rendered.bytes())));
            renditions.add(new ImageRendition(rendered.edge(), rendered.width(), rendered.height(), file));
        }
        int order = product.images().stream().mapToInt(ProductImage::sortOrder).max().orElse(-1) + 1;
        var image = new ProductImage(Identifiers.newId(), null, order, stored, renditions, key, checksum);
        product.addImage(image);
        products.save(product);
        return image;
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
