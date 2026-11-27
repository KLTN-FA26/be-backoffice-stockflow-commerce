package com.stockflow.catalog.internal.controller;

import com.stockflow.catalog.internal.controller.dto.*;
import com.stockflow.catalog.internal.domain.Listing;
import com.stockflow.catalog.internal.service.CatalogCommerceService;
import com.stockflow.catalog.internal.service.CatalogSearchService;
import com.stockflow.product.api.PublishedProductImage;

import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class CatalogCommerceWebMapper {
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
        return new CatalogCardResponse(c.productId(), c.slug(), c.title(), c.seoTitle(), c.primaryImageUrl(),
                c.skus().stream().map(s -> new CatalogCardResponse.CardSku(s.sku(), s.name())).toList(), c.kind());
    }

    public SearchSuggestionResponse toResponse(CatalogSearchService.Suggestion s) {
        return new SearchSuggestionResponse(s.title(), s.slug(), s.sku());
    }

    public CatalogProductResponse toResponse(CatalogCommerceService.PublicView v) {
        var page = v.page();
        return new CatalogProductResponse(
                v.productId(),
                v.slug(),
                v.title(),
                page.descriptionHtml(),
                v.seoTitle(),
                v.seoDescription(),
                page.brand() == null ? null : new CatalogProductResponse.Brand(page.brand().id(), page.brand().name(),
                        page.brand().slug(), page.brand().logoUrl()),
                page.category() == null ? null : new CatalogProductResponse.Category(page.category().id(),
                        page.category().name(), page.category().slug()),
                page.attributes().stream().map(a -> new CatalogProductResponse.Attribute(a.code(), a.name(),
                        a.dataType(), a.unit(), a.values(), a.text(), a.number(), a.bool())).toList(),
                page.variants().stream().map(s -> new CatalogProductResponse.VariantResponse(s.variantId(), s.sku(),
                        s.name(), s.descriptionHtml(), s.ownDescription(), s.options().stream()
                        .map(o -> new CatalogProductResponse.Option(o.axisCode(), o.axisName(), o.valueCode(),
                                o.label())).toList())).toList(),
                "/api/v1/catalog/products/" + v.slug() + "/gallery",
                v.kind());
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
