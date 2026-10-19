package com.stockflow.product.internal.controller;

import com.stockflow.common.storage.DownloadLink;
import com.stockflow.product.internal.controller.dto.BrandResponse;
import com.stockflow.product.internal.controller.dto.CategoryResponse;
import com.stockflow.product.internal.controller.dto.GeneratedVariantsResponse;
import com.stockflow.product.internal.controller.dto.ImageDownloadResponse;
import com.stockflow.product.internal.controller.dto.MediaResponse;
import com.stockflow.product.internal.controller.dto.PublicGalleryResponse;
import com.stockflow.product.internal.controller.dto.VariantOptionResponse;
import com.stockflow.product.internal.controller.dto.VariantResponse;
import com.stockflow.product.internal.service.ProductMediaService;
import com.stockflow.product.internal.service.ProductTaxonomyService;
import com.stockflow.product.internal.service.ProductVariantService;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.UUID;

/**
 * Hand-written DTO mapping for the product module's secondary resources: variants, media, brands,
 * categories. Each is a one-to-one copy of a service view that MapStruct would not
 * make shorter, and the media one has to unwrap the stored file.
 */
@Component
class ProductAdminWebMapper {

    VariantResponse toResponse(ProductVariantService.VariantView v, UUID inventoryItemId) {
        return new VariantResponse(v.id(), v.productId(), v.sku(), v.name(), v.status().name(), v.defaultVariant(),
                v.attributeSignature(), v.position(), v.obsoletedAt(), v.version(),
                v.options().stream().map(o -> new VariantOptionResponse(o.attributeId(), o.attributeCode(),
                        o.valueId(), o.valueCode(), o.label())).toList(), inventoryItemId);
    }

    GeneratedVariantsResponse toResponse(ProductVariantService.Generated g, Map<String, UUID> inventoryItemIds) {
        return new GeneratedVariantsResponse(
                g.created().stream().map(v -> toResponse(v, inventoryItemIds.get(v.sku()))).toList(), g.skipped());
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
}
