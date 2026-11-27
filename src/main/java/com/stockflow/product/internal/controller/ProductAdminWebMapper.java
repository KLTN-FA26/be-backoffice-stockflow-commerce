package com.stockflow.product.internal.controller;

import com.stockflow.common.storage.DownloadLink;
import com.stockflow.product.internal.controller.dto.BrandResponse;
import com.stockflow.product.internal.controller.dto.CategoryResponse;
import com.stockflow.product.internal.controller.dto.GeneratedVariantsResponse;
import com.stockflow.product.internal.controller.dto.ImageDownloadResponse;
import com.stockflow.product.internal.controller.dto.MediaResponse;
import com.stockflow.product.internal.controller.dto.ProductImageResponse;
import com.stockflow.product.internal.controller.dto.PublicGalleryResponse;
import com.stockflow.product.internal.controller.dto.VariantOptionResponse;
import com.stockflow.product.internal.controller.dto.VariantResponse;
import com.stockflow.product.internal.service.ProductImageService;
import com.stockflow.product.internal.service.ProductMediaService;
import com.stockflow.product.internal.service.ProductTaxonomyService;
import com.stockflow.product.internal.service.ProductVariantService;
import org.springframework.stereotype.Component;


/**
 * Hand-written DTO mapping for the product module's secondary resources: variants, media, brands,
 * categories. Each is a one-to-one copy of a service view that MapStruct would not
 * make shorter, and the media one has to unwrap the stored file.
 */
@Component
class ProductAdminWebMapper {

    VariantResponse toResponse(ProductVariantService.VariantView v) {
        return new VariantResponse(v.id(), v.productId(), v.sku(), v.name(), v.status().name(), v.defaultVariant(),
                v.attributeSignature(), v.position(), v.obsoletedAt(), v.version(),
                v.options().stream().map(o -> new VariantOptionResponse(o.attributeId(), o.attributeCode(),
                        o.valueId(), o.valueCode(), o.label())).toList(), v.rotatable(), v.description());

    }

    GeneratedVariantsResponse toResponse(ProductVariantService.Generated g) {
        return new GeneratedVariantsResponse(g.created().stream().map(this::toResponse).toList(), g.skipped());
    }

    /** The people's names come from {@link ProductMediaResponses}, which asks identity once per list. */
    MediaResponse toResponse(ProductMediaService.MediaView m, String uploadedByName, String publishedByName) {
        var file = m.original();
        return new MediaResponse(m.id(), m.variantId(), m.kind(), m.url(),
                file == null ? null : file.originalName(), file == null ? null : file.contentType(),
                file == null ? null : file.sizeBytes(), file == null ? null : file.storedAt(),
                m.renditions().stream().map(r -> new MediaResponse.Rendition(r.edge(), r.width(), r.height(),
                        r.file().contentType(), r.file().sizeBytes(), m.renditionUrls().get(r.edge()))).toList(),
                m.altText(), m.sortOrder(), m.primary(), m.published(), m.publishedAt(), m.publishedBy(),
                m.uploadedBy(), m.uploadedAt(), m.version(), uploadedByName, publishedByName);
    }

    ProductImageResponse toResponse(ProductImageService.ProductImageView i) {
        var file = i.original();
        return new ProductImageResponse(i.productId(), file.originalName(), file.contentType(), file.sizeBytes(),
                file.storedAt(), i.altText(), i.renditions().stream()
                .map(r -> new ProductImageResponse.Rendition(r.edge(), r.width(), r.height(), r.file().contentType(),
                        r.file().sizeBytes(), i.renditionUrls().get(r.edge())))
                .toList());
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
