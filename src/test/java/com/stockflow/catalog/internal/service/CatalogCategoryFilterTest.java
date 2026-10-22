package com.stockflow.catalog.internal.service;

import com.stockflow.catalog.internal.repository.CatalogSearchRepository;
import com.stockflow.catalog.internal.repository.CatalogSearchRepository.Candidate;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.inventory.api.InventoryService;
import com.stockflow.product.api.CategoryNavigation;
import com.stockflow.product.api.ProductPublication;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** SCRUM-171: browsing the catalogue by category covers the category's whole visible subtree, in the query itself. */
class CatalogCategoryFilterTest {

    private final CatalogSearchRepository repository = mock(CatalogSearchRepository.class);
    private final ProductPublication products = mock(ProductPublication.class);
    private final CategoryNavigation categories = mock(CategoryNavigation.class);
    private final CatalogSearchService service = new CatalogSearchService(repository, products,
            mock(InventoryService.class), new SearchProperties(1.0, 1.0, 10.0, 0.25, 0.2, 500), categories);

    @Test
    @DisplayName("the listing asks the database for the subtree's products only")
    void restrictsTheQueryToTheSubtree() {
        UUID cups = UUID.randomUUID();
        UUID paperCups = UUID.randomUUID();
        UUID product = UUID.randomUUID();
        when(categories.visibleSubtree("cups")).thenReturn(Optional.of(Set.of(cups, paperCups)));
        when(categories.productsIn(Set.of(cups, paperCups))).thenReturn(Set.of(product));
        var card = new Candidate(product, "ly-giay", "Ly giấy", null, 0, List.of("CUP-1"), null, null);
        when(repository.listAll(any(), anyInt(), eq(Set.of(product)))).thenReturn(List.of(card));
        when(products.published(any())).thenAnswer(call -> call.getArgument(0));
        // Still sellable when the page's cards are read (#134): one active SKU.
        when(products.cards(Set.of(product))).thenReturn(java.util.Map.of(product,
                new com.stockflow.product.api.PublishedProductCard(null,
                        List.of(new com.stockflow.product.api.PublishedProductCard.Sku("CUP-1", "Ly giấy")))));

        var page = service.search(null, null, 0, 20, "cups");

        assertThat(page.items()).extracting(CatalogCommerceService.Card::productId).containsExactly(product);
        verify(repository, never()).listAll(any(), anyInt());
    }

    @Test
    @DisplayName("an unknown or hidden category is CATEGORY_NOT_FOUND; an empty one is an empty page")
    void unknownAndEmpty() {
        when(categories.visibleSubtree("gone")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.search(null, null, 0, 20, "gone"))
                .isInstanceOfSatisfying(BusinessException.class,
                        refused -> assertThat(refused.errorCode()).isEqualTo(ErrorCode.CATEGORY_NOT_FOUND));

        UUID empty = UUID.randomUUID();
        when(categories.visibleSubtree("empty")).thenReturn(Optional.of(Set.of(empty)));
        when(categories.productsIn(Set.of(empty))).thenReturn(Set.of());
        assertThat(service.search("ly", null, 0, 20, "empty").items()).isEmpty();
        verify(repository, never()).match(any(), any(), anyInt(), any());
    }
}
