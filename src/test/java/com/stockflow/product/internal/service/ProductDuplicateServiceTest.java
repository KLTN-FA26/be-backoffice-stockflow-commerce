package com.stockflow.product.internal.service;

import com.stockflow.product.api.ProductKind;
import com.stockflow.product.api.ProductStatus;
import com.stockflow.product.api.ProductSummary;
import com.stockflow.product.api.TaxClass;
import com.stockflow.product.internal.repository.ProductSearchRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Which products {@link ProductDuplicateService} reports as possible duplicates (SCRUM-88), with the
 * candidates the repository would load mocked. The query itself is in the integration test.
 */
class ProductDuplicateServiceTest {

    private static final UUID BRAND = UUID.randomUUID();
    private static final UUID CATEGORY = UUID.randomUUID();
    private static final Instant NOW = Instant.parse("2026-10-28T03:00:00Z");

    private final ProductSearchRepository search = mock(ProductSearchRepository.class);
    private final ProductDuplicateService service = new ProductDuplicateService(search);

    private static ProductSummary product(String code, String name) {
        return new ProductSummary(UUID.randomUUID(), code, name, null, code.toLowerCase(), BRAND, "Brand", CATEGORY,
                null, null, null, TaxClass.STANDARD, ProductKind.STANDARD, ProductStatus.DRAFT, NOW, "u", NOW, "u",
                null, null, null, null, null, null, null, 0L, null, null, null);
    }

    @Test
    @DisplayName("of the same brand and category, only the products whose name has the same key are reported, by code")
    void matchesSameKeyOnly() {
        ProductSummary second = product("CUP-B", "LY GIẤY 12 OZ");
        ProductSummary first = product("CUP-A", "Ly giay 12oz");
        ProductSummary bigger = product("CUP-C", "Ly giấy 16oz");
        when(search.sameBrandAndCategory(BRAND, CATEGORY)).thenReturn(List.of(second, bigger, first));

        assertThat(service.possibleDuplicates("Ly giấy 12oz", BRAND, CATEGORY, null))
                .extracting(ProductSummary::code).containsExactly("CUP-A", "CUP-B");
    }

    @Test
    @DisplayName("the product being edited is never its own duplicate")
    void excludesTheProductItself() {
        ProductSummary self = product("CUP-A", "Ly giấy 12oz");
        when(search.sameBrandAndCategory(BRAND, CATEGORY)).thenReturn(List.of(self));

        assertThat(service.possibleDuplicates("Ly giấy 12oz", BRAND, CATEGORY, self.productId())).isEmpty();
    }

    @Test
    @DisplayName("without a brand or a category there is nothing to compare with yet, and nothing is loaded")
    void noBrandOrCategoryNoCandidates() {
        assertThat(service.possibleDuplicates("Ly giấy 12oz", null, CATEGORY, null)).isEmpty();
        assertThat(service.possibleDuplicates("Ly giấy 12oz", BRAND, null, null)).isEmpty();
        verify(search, never()).sameBrandAndCategory(any(), any());
    }

    @Test
    @DisplayName("a name that is only punctuation matches nothing, not every other such name")
    void emptyKeyMatchesNothing() {
        assertThat(service.possibleDuplicates("!!!", BRAND, CATEGORY, null)).isEmpty();
        assertThat(service.possibleDuplicates(null, BRAND, CATEGORY, null)).isEmpty();
        verify(search, never()).sameBrandAndCategory(any(), any());
    }
}
