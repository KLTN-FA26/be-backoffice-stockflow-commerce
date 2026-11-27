package com.stockflow.order.internal.service;

import com.stockflow.product.api.CustomerSku;
import com.stockflow.product.api.CustomerSkus;
import com.stockflow.product.api.ProductService;
import com.stockflow.product.api.ProductSummary;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class LineNamesTest {

    private final ProductService products = mock(ProductService.class);
    private final CustomerSkus customerSkus = mock(CustomerSkus.class);
    private final LineNames names = new LineNames(products, customerSkus);

    private static ProductSummary product(UUID id, String name) {
        return new ProductSummary(id, "LY12", name, null, "ly12", null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null, null, null, 1, null, null, null, null);
    }

    private static CustomerSku own(String sku, UUID productId, String name) {
        return new CustomerSku(UUID.randomUUID(), productId, sku, name, UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), "LY12-BASE", "ACTIVE");
    }

    @Test
    void eachSkuIsNamedByItsProductAndItselfWithOneProductReadPerProductNotPerLine() {
        UUID cup = UUID.randomUUID();
        when(products.variantNamesOf(anyCollection())).thenReturn(Map.of("LY12-ACME-01", "Cup 12oz Acme blue",
                "LY12-ACME-02", "Cup 12oz Acme red"));
        when(customerSkus.findAll(anyCollection())).thenReturn(Map.of(
                "LY12-ACME-01", own("LY12-ACME-01", cup, "Cup 12oz Acme blue"),
                "LY12-ACME-02", own("LY12-ACME-02", cup, "Cup 12oz Acme red")));
        when(products.findById(cup)).thenReturn(Optional.of(product(cup, "Paper cup 12oz")));
        when(products.nameForSku("LEGACY-BLANK")).thenReturn(Optional.of("Blank cup"));

        Map<String, LineNames.Names> found = names.of(List.of("LY12-ACME-01", "LY12-ACME-02", "LY12-ACME-01",
                "LEGACY-BLANK", "UNKNOWN"));

        assertThat(found.get("LY12-ACME-01")).isEqualTo(new LineNames.Names("Paper cup 12oz", "Cup 12oz Acme blue"));
        assertThat(found.get("LY12-ACME-02")).isEqualTo(new LineNames.Names("Paper cup 12oz", "Cup 12oz Acme red"));
        // Not a customer's own SKU: its product is asked by the SKU; it has no variant name here.
        assertThat(found.get("LEGACY-BLANK")).isEqualTo(new LineNames.Names("Blank cup", null));
        assertThat(found).doesNotContainKey("UNKNOWN");
        assertThat(LineNames.of(found, "UNKNOWN")).isEqualTo(LineNames.Names.NONE);
        assertThat(LineNames.of(found, null)).isEqualTo(LineNames.Names.NONE);
        verify(products).variantNamesOf(anyCollection());
        verify(customerSkus).findAll(anyCollection());
        verify(products, times(1)).findById(any());
    }

    @Test
    void nothingAskedIsNothingRead() {
        assertThat(names.of(List.of())).isEmpty();
        assertThat(names.of(Arrays.asList((String) null))).isEmpty();
        assertThat(names.of(null)).isEmpty();
        verifyNoInteractions(products, customerSkus);
    }
}
