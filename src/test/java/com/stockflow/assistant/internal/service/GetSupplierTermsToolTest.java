package com.stockflow.assistant.internal.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stockflow.common.api.PageResponse;
import com.stockflow.common.error.BusinessException;
import com.stockflow.inventory.api.ReplenishmentEligibility;
import com.stockflow.procurement.api.SkuSupplierSummary;
import com.stockflow.procurement.api.SupplierItemService;
import com.stockflow.procurement.api.SupplierItemSummary;
import com.stockflow.procurement.api.SupplierService;
import com.stockflow.procurement.api.SupplierSummary;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * getSupplierTerms reads the supplier catalog as a buyer does: who sells a SKU, what one supplier sells, whether a row
 * can go on a new purchase order at all, and never a guessed supplier or a supplier's personal data.
 */
class GetSupplierTermsToolTest {

    private static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules();
    private static final UUID GOVIET = UUID.randomUUID();
    private static final UUID OLDCO = UUID.randomUUID();

    private final SupplierItemService catalog = mock(SupplierItemService.class);
    private final SupplierService suppliers = mock(SupplierService.class);
    private final ReplenishmentEligibility eligibility = mock(ReplenishmentEligibility.class);
    @SuppressWarnings("unchecked")
    private final ObjectProvider<ReplenishmentEligibility> owner = mock(ObjectProvider.class);
    private final GetSupplierTermsTool tool = new GetSupplierTermsTool(catalog, suppliers, owner);

    @BeforeEach
    void everySkuMayBeBought() {
        when(owner.getIfAvailable()).thenReturn(eligibility);
        when(eligibility.replenishable(any())).thenAnswer(call -> Set.copyOf(call.<Collection<String>>getArgument(0)));
    }

    private static ToolArguments args(String sku, String supplier) {
        return ToolArguments.parse(JSON, "{\"sku\":\"" + sku + "\",\"supplier\":\"" + supplier + "\"}");
    }

    private static SkuSupplierSummary offer(UUID supplierId, String code, String status, boolean preferred, int moq) {
        return new SkuSupplierSummary(supplierId, code, code + " name", status, UUID.randomUUID(), code + "-L90",
                new BigDecimal("400.00"), "VND", moq, 100, 21, preferred);
    }

    private static SupplierSummary supplier(UUID id, String code, String name) {
        return new SupplierSummary(id, code, name, "Contact Person", "sales@example.test", "0900000002",
                "0300000001", "ACTIVE", 30, 14, "EMAIL", "https://supplier.example.test/api", BigDecimal.ZERO,
                false, BigDecimal.ZERO, Instant.now(), Instant.now());
    }

    private static SupplierItemSummary listed(UUID supplierId, String sku) {
        return new SupplierItemSummary(UUID.randomUUID(), supplierId, UUID.randomUUID(), sku, "GV-" + sku,
                new BigDecimal("1800.00"), "VND", 500, 50, null, 14, true, 0, Instant.now(), Instant.now());
    }

    private void found(String text, SupplierSummary... matches) {
        when(suppliers.list(0, SupplierMatch.MAX_CANDIDATES + 1, text, null, null))
                .thenReturn(PageResponse.of(List.of(matches), 0, SupplierMatch.MAX_CANDIDATES + 1, matches.length));
    }

    @Test
    @DisplayName("by SKU: every supplier's terms, field by field, and an inactive supplier's offer is not orderable")
    void bySku() throws Exception {
        when(catalog.suppliersOf("EVAL-LID-90")).thenReturn(List.of(offer(GOVIET, "GOVIET", "ACTIVE", true, 1000),
                offer(OLDCO, "OLDCO", "INACTIVE", false, 500)));

        GetSupplierTermsTool.Answer answer = tool.run(args("eval-lid-90", ""));

        assertThat(answer.terms()).containsExactly(
                new GetSupplierTermsTool.Row("EVAL-LID-90", "GOVIET", "GOVIET name", "ACTIVE", true, "GOVIET-L90",
                        new BigDecimal("400.00"), "VND", 1000, 100, 21, true, true),
                new GetSupplierTermsTool.Row("EVAL-LID-90", "OLDCO", "OLDCO name", "INACTIVE", false, "OLDCO-L90",
                        new BigDecimal("400.00"), "VND", 500, 100, 21, true, false));
        assertThat(answer.total()).isEqualTo(2);
        assertThat(answer.truncated()).isFalse();
        assertThat(answer.note()).isNull();
        assertThat(JSON.writeValueAsString(answer)).as("no ids leave").doesNotContain(GOVIET.toString(),
                OLDCO.toString());
    }

    @Test
    @DisplayName("a SKU that may not be bought any more is on no orderable row, whoever sells it")
    void notPurchasable() {
        doReturn(Set.of()).when(eligibility).replenishable(any());
        when(catalog.suppliersOf("EVAL-LID-90")).thenReturn(List.of(offer(GOVIET, "GOVIET", "ACTIVE", true, 1000)));

        GetSupplierTermsTool.Row row = tool.run(args("EVAL-LID-90", "")).terms().getFirst();

        assertThat(row.skuPurchasable()).isFalse();
        assertThat(row.orderable()).isFalse();
    }

    @Test
    @DisplayName("a SKU nobody lists says that no purchase order can take it")
    void nobodyListsIt() {
        when(catalog.suppliersOf("EVAL-LID-90")).thenReturn(List.of());

        GetSupplierTermsTool.Answer answer = tool.run(args("EVAL-LID-90", ""));

        assertThat(answer.terms()).isEmpty();
        assertThat(answer.note()).contains("No supplier lists EVAL-LID-90");
    }

    @Test
    @DisplayName("by supplier: what it sells, with the total and whether rows were left out; no personal data")
    void bySupplier() throws Exception {
        found("Xưởng gỗ Việt", supplier(GOVIET, "GOVIET", "Xưởng gỗ Việt"));
        when(catalog.list(GOVIET, 0, GetSupplierTermsTool.MAX_ROWS, null))
                .thenReturn(PageResponse.of(List.of(listed(GOVIET, "EVAL-CUP-12OZ"), listed(GOVIET, "EVAL-LID-90")),
                        0, GetSupplierTermsTool.MAX_ROWS, 60));

        GetSupplierTermsTool.Answer answer = tool.run(args("", "Xưởng gỗ Việt"));

        assertThat(answer.terms()).extracting(GetSupplierTermsTool.Row::sku)
                .containsExactly("EVAL-CUP-12OZ", "EVAL-LID-90");
        assertThat(answer.terms().getFirst()).isEqualTo(new GetSupplierTermsTool.Row("EVAL-CUP-12OZ", "GOVIET",
                "Xưởng gỗ Việt", "ACTIVE", true, "GV-EVAL-CUP-12OZ", new BigDecimal("1800.00"), "VND", 500, 50, 14,
                true, true));
        assertThat(answer.total()).isEqualTo(60);
        assertThat(answer.truncated()).isTrue();
        assertThat(JSON.writeValueAsString(answer)).doesNotContain("Contact Person", "sales@example.test",
                "0900000002", "0300000001", "supplier.example.test", GOVIET.toString());
    }

    @Test
    @DisplayName("several suppliers match: the candidates come back and no catalog is read, nothing is guessed")
    void severalSuppliers() {
        found("Xưởng", supplier(GOVIET, "GOVIET", "Xưởng gỗ Việt"), supplier(OLDCO, "INNHANH", "Xưởng in Nhanh"));

        GetSupplierTermsTool.Answer answer = tool.run(args("EVAL-LID-90", "Xưởng"));

        assertThat(answer.terms()).isEmpty();
        assertThat(answer.supplierCandidates()).extracting(SupplierMatch.Candidate::supplierCode)
                .containsExactly("GOVIET", "INNHANH");
        assertThat(answer.note()).contains("Several suppliers match");
        verify(catalog, never()).suppliersOf(anyString());
        verify(catalog, never()).list(any(), anyInt(), anyInt(), any());
    }

    @Test
    @DisplayName("an exact code wins over other suppliers the text also matches")
    void exactCodeWins() {
        found("goviet", supplier(OLDCO, "GOVIET2", "Gỗ Việt cũ"), supplier(GOVIET, "GOVIET", "Xưởng gỗ Việt"));
        when(catalog.suppliersOf("EVAL-LID-90")).thenReturn(List.of(offer(GOVIET, "GOVIET", "ACTIVE", true, 1000),
                offer(OLDCO, "GOVIET2", "ACTIVE", false, 500)));

        GetSupplierTermsTool.Answer answer = tool.run(args("EVAL-LID-90", "goviet"));

        assertThat(answer.terms()).extracting(GetSupplierTermsTool.Row::supplierCode).containsExactly("GOVIET");
    }

    @Test
    @DisplayName("a SKU and a supplier: only that supplier's offer, or a note when it does not list the SKU")
    void skuAndSupplier() {
        found("INNHANH", supplier(OLDCO, "INNHANH", "Xưởng in Nhanh"));
        when(catalog.suppliersOf("EVAL-LID-90")).thenReturn(List.of(offer(GOVIET, "GOVIET", "ACTIVE", true, 1000)));

        GetSupplierTermsTool.Answer answer = tool.run(args("EVAL-LID-90", "INNHANH"));

        assertThat(answer.terms()).isEmpty();
        assertThat(answer.total()).isZero();
        assertThat(answer.note()).contains("INNHANH does not list EVAL-LID-90");
    }

    @Test
    @DisplayName("neither a SKU nor a supplier is a request the model must correct")
    void needsOneOfThem() {
        assertThatThrownBy(() -> tool.run(args("", " "))).isInstanceOf(BusinessException.class);
        verify(suppliers, never()).list(anyInt(), anyInt(), any(), any(), any());
        verify(catalog, never()).suppliersOf(anyString());
    }

    @Test
    @DisplayName("without a product module every SKU counts as purchasable, as the stock alerts assume")
    void noProductModule() {
        when(owner.getIfAvailable()).thenReturn(null);
        when(catalog.suppliersOf("EVAL-LID-90")).thenReturn(List.of(offer(GOVIET, "GOVIET", "ACTIVE", true, 1000)));

        assertThat(tool.run(args("EVAL-LID-90", "")).terms().getFirst().orderable()).isTrue();
    }
}
