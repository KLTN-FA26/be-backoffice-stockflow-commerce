package com.stockflow.catalog.internal.controller;

import com.stockflow.catalog.internal.controller.dto.*;
import com.stockflow.catalog.internal.domain.Listing;
import com.stockflow.catalog.internal.domain.SellingPrice;
import com.stockflow.catalog.internal.service.CatalogCommerceService;
import com.stockflow.product.api.PublishedProductImage;

import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class CatalogWebMapper {
    public SkuPriceResponse toResponse(CatalogCommerceService.PriceView v) {
        return new SkuPriceResponse(
                v.sku(),
                v.revision(),
                v.basePrice() == null ? null : v.basePrice().amount(),
                v.basePrice() == null ? null : v.basePrice().currency(),
                v.effectivePrice() == null ? null : v.effectivePrice().amount(),
                v.effectivePrice() == null ? null : v.effectivePrice().currency());
    }

    public SellingPrice toPrice(SetBasePriceRequest r) {
        return new SellingPrice(r.price(), r.currency());
    }

    public ListingResponse toResponse(Listing l) {
        return new ListingResponse(
                l.productId(),
                l.slug(),
                l.seoTitle(),
                l.seoDescription(),
                l.revision(),
                l.projectedRevision(),
                l.enabled(),
                l.revision() != l.projectedRevision());
    }

    public CatalogCardResponse toResponse(CatalogCommerceService.Card c) {
        return new CatalogCardResponse(c.productId(), c.slug(), c.title(), c.seoTitle());
    }

    public CatalogProductResponse toResponse(CatalogCommerceService.PublicView v) {
        return new CatalogProductResponse(
                v.productId(),
                v.slug(),
                v.title(),
                v.description(),
                v.seoTitle(),
                v.seoDescription(),
                v.variants().stream()
                        .map(
                                s ->
                                        new CatalogProductResponse.VariantResponse(
                                                s.sku(),
                                                s.price().amount(),
                                                s.price().currency(),
                                                s.availability()))
                        .toList(),
                "/api/v1/public/catalog/products/" + v.slug() + "/gallery");
    }

    public CatalogGalleryResponse toGalleryResponse(List<PublishedProductImage> images) {
        return new CatalogGalleryResponse(
                images.stream()
                        .map(
                                m ->
                                        new CatalogGalleryResponse.Image(
                                                m.id(),
                                                m.variantId(),
                                                m.sku(),
                                                m.url(),
                                                m.altText(),
                                                m.sortOrder(),
                                                m.primary()))
                        .toList());
    }
}
