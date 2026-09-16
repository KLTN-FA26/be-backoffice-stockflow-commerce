package com.stockflow.product.internal.service;

import com.stockflow.common.audit.AuditAction;
import com.stockflow.common.audit.Auditable;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.id.Identifiers;
import com.stockflow.common.storage.DownloadLink;
import com.stockflow.common.storage.FileCategory;
import com.stockflow.common.storage.FileTransfers;
import com.stockflow.common.storage.FileUpload;
import com.stockflow.product.api.ProductStatus;
import com.stockflow.product.internal.domain.Product;
import com.stockflow.product.internal.domain.ProductId;
import com.stockflow.product.internal.domain.ProductImage;
import com.stockflow.product.internal.domain.ProductRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/** Module-local use case: HTTP uploads do not enlarge the cross-module product contract. */
@Service
@Transactional
public class ProductImageService {
    private final ProductRepository products;
    private final FileTransfers files;

    public ProductImageService(ProductRepository products, FileTransfers files) {
        this.products = products;
        this.files = files;
    }

    @Auditable(action = AuditAction.UPDATE, resourceType = "product", resourceId = "#productId")
    public ProductImage upload(UUID productId, FileUpload upload) {
        Product product = requireProduct(productId);
        if (product.status() != ProductStatus.DRAFT) {
            throw new BusinessException(ErrorCode.INVALID_PRODUCT_STATUS_TRANSITION,
                    "Only draft products accept image changes");
        }
        var stored = files.store(FileCategory.PRODUCT_IMAGE, upload);
        int order = product.images().stream().mapToInt(ProductImage::sortOrder).max().orElse(-1) + 1;
        var image = new ProductImage(Identifiers.newId(), null, order, stored);
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
