package com.stockflow.catalog.internal.service;

import com.stockflow.catalog.api.CatalogService;
import com.stockflow.catalog.internal.repository.ListingRepository;
import com.stockflow.product.api.ProductPublication;
import com.stockflow.product.api.PublishedProductCard;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** SCRUM-190: where the storefront shows a SKU today, for putting a past order back in a basket. */
class CatalogServiceListingsTest {

    private final ListingRepository listings = mock(ListingRepository.class);
    private final ProductPublication products = mock(ProductPublication.class);
    private final CatalogServiceImpl service = new CatalogServiceImpl(listings, products, null);

    @Test
    @DisplayName("a SKU on sale comes back with its slug, title and primary image; one that is not has no entry")
    void onlyWhatIsOnSale() {
        UUID product = UUID.randomUUID();
        when(listings.listedBySku(Set.of("CUP-8OZ", "CUP-OLD"))).thenReturn(Map.of("CUP-8OZ",
                new ListingRepository.ListedSku("CUP-8OZ", product, "ly-giay-8oz", "Ly giấy 8oz")));
        when(products.cards(Set.of(product))).thenReturn(Map.of(product,
                new PublishedProductCard("https://cdn.example/ly.jpg", List.of(), com.stockflow.product.api.ProductKind.STANDARD)));

        Map<String, CatalogService.SkuListing> found = service.listings(Set.of("CUP-8OZ", "CUP-OLD"));

        assertThat(found).containsOnlyKeys("CUP-8OZ");
        assertThat(found.get("CUP-8OZ")).isEqualTo(
                new CatalogService.SkuListing("ly-giay-8oz", "Ly giấy 8oz", "https://cdn.example/ly.jpg"));
    }

    @Test
    @DisplayName("a product without a card still lists, with no image")
    void noImage() {
        UUID product = UUID.randomUUID();
        when(listings.listedBySku(any())).thenReturn(Map.of("CUP-8OZ",
                new ListingRepository.ListedSku("CUP-8OZ", product, "ly-giay-8oz", "Ly giấy 8oz")));
        when(products.cards(any())).thenReturn(Map.of());

        assertThat(service.listings(Set.of("CUP-8OZ")).get("CUP-8OZ").imageUrl()).isNull();
    }

    @Test
    @DisplayName("nothing on sale asks the product module for nothing")
    void nothingOnSale() {
        when(listings.listedBySku(any())).thenReturn(Map.of());
        assertThat(service.listings(Set.of("CUP-OLD"))).isEmpty();
        verifyNoInteractions(products);
    }
}
