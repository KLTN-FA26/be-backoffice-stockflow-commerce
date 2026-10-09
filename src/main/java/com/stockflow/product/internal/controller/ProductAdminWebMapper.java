package com.stockflow.product.internal.controller;

import com.stockflow.common.storage.DownloadLink;
import com.stockflow.inventory.api.InventoryItemLogistics;
import com.stockflow.inventory.api.ItemLogistics;
import com.stockflow.product.internal.controller.dto.BrandResponse;
import com.stockflow.product.internal.controller.dto.CategoryResponse;
import com.stockflow.product.internal.controller.dto.ImageDownloadResponse;
import com.stockflow.product.internal.controller.dto.MediaResponse;
import com.stockflow.product.internal.controller.dto.PublicGalleryResponse;
import com.stockflow.product.internal.controller.dto.SkuLogisticsRequest;
import com.stockflow.product.internal.controller.dto.SkuLogisticsResponse;
import com.stockflow.product.internal.controller.dto.VariantResponse;
import com.stockflow.product.internal.service.ProductMediaService;
import com.stockflow.product.internal.service.ProductTaxonomyService;
import com.stockflow.product.internal.service.ProductVariantService;
import org.springframework.stereotype.Component;

import java.util.Objects;
import java.util.UUID;

/**
 * Hand-written DTO mapping for the product module's secondary resources: variants, media, brands,
 * categories, SKU logistics. Each is a one-to-one copy of a service view that MapStruct would not
 * make shorter, and the media one has to unwrap the stored file.
 */
@Component
class ProductAdminWebMapper {

    VariantResponse toResponse(ProductVariantService.VariantView v) {
        return new VariantResponse(v.id(), v.productId(), v.sku(), v.name(), v.status().name(), v.defaultVariant(),
                v.attributeSignature(), v.position(), v.obsoletedAt(), v.version());
    }

    MediaResponse toResponse(ProductMediaService.MediaView m) {
        var file = m.original();
        return new MediaResponse(m.id(), m.variantId(), m.kind(), m.url(),
                file == null ? null : file.originalName(), file == null ? null : file.contentType(),
                file == null ? null : file.sizeBytes(), file == null ? null : file.storedAt(),
                m.renditions().stream().map(r -> new MediaResponse.Rendition(r.edge(), r.width(), r.height(),
                        r.file().contentType(), r.file().sizeBytes())).toList(),
                m.altText(), m.sortOrder(), m.primary(), m.published(), m.publishedAt(), m.publishedBy(),
                m.uploadedBy(), m.uploadedAt(), m.version());
    }

    PublicGalleryResponse toResponse(ProductMediaService.PublicGallery g) {
        return new PublicGalleryResponse(g.productId(), g.variantId(), g.sku(), g.images().stream()
                .map(i -> new PublicGalleryResponse.Image(i.imageId(), i.altText(), i.primary(), i.url(),
                        i.renditions().stream().map(r -> new PublicGalleryResponse.Rendition(r.edge(), r.width(),
                                r.height(), r.contentType(), r.url())).toList()))
                .toList());
    }

    BrandResponse toResponse(ProductTaxonomyService.BrandView b) {
        return new BrandResponse(b.id(), b.code(), b.name(), b.slug(), b.logoUrl(), b.active(), b.version());
    }

    CategoryResponse toResponse(ProductTaxonomyService.CategoryView c) {
        return new CategoryResponse(c.id(), c.parentId(), c.code(), c.name(), c.slug(), c.path(), c.depth(),
                c.sortOrder(), c.imageUrl(), c.seoTitle(), c.seoDescription(), c.active(), c.version());
    }

    ImageDownloadResponse toResponse(DownloadLink link) {
        return new ImageDownloadResponse(link.url(), link.expiresAt());
    }

    SkuLogisticsResponse toResponse(UUID skuId, InventoryItemLogistics item) {
        ItemLogistics l = item.logistics();
        return new SkuLogisticsResponse(skuId, item.sku(), item.version(), l.unitOfMeasure(), l.barcode(),
                l.weightKg(), l.lengthCm(), l.widthCm(), l.heightCm(), l.packageWeightKg(), l.packageLengthCm(),
                l.packageWidthCm(), l.packageHeightCm(), l.packageCount(), l.packSize(), l.storageClass(),
                l.requiresAdultSignature(), l.shippingRestrictionNote(), l.qcRequired());
    }

    ItemLogistics toLogistics(SkuLogisticsRequest r) {
        return new ItemLogistics(r.unitOfMeasure(), r.barcode(), r.weightKg(), r.lengthCm(), r.widthCm(),
                r.heightCm(), r.packageWeightKg(), r.packageLengthCm(), r.packageWidthCm(), r.packageHeightCm(),
                Objects.requireNonNullElse(r.packageCount(), 1), Objects.requireNonNullElse(r.packSize(), 1),
                r.storageClass(), r.requiresAdultSignature(), r.shippingRestrictionNote(), r.qcRequired());
    }
}
