package com.stockflow.procurement.internal.service;

import com.stockflow.common.api.FieldError;
import com.stockflow.common.domain.Money;
import com.stockflow.common.domain.Sku;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.procurement.internal.domain.PackingUnit;
import com.stockflow.procurement.internal.domain.PoLine;
import com.stockflow.procurement.internal.domain.PoLineStatus;
import com.stockflow.procurement.internal.domain.PurchaseOrder;
import com.stockflow.procurement.internal.domain.PurchaseOrderId;
import com.stockflow.procurement.internal.domain.PurchaseOrderStatus;
import com.stockflow.procurement.internal.domain.SupplierConfirmationStatus;
import com.stockflow.procurement.internal.domain.SupplierItem;
import com.stockflow.procurement.internal.domain.SupplierItemRepository;
import com.stockflow.procurement.internal.domain.SupplierItemTerms;
import com.stockflow.product.api.ProductService;
import com.stockflow.product.api.Purchasability;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The catalog and purchasability checks of an amendment's changed lines (SCRUM-117), with the catalog and the product
 * module mocked and a real {@link PurchaseOrder} proposing the amendment.
 */
class AmendmentLineChecksTest {

    private static final Instant NOW = Instant.parse("2026-10-01T03:00:00Z");
    private static final LocalDate ORDER_DATE = LocalDate.of(2026, 10, 1);
    private static final LocalDate EXPECTED = ORDER_DATE.plusDays(7);
    private static final UUID SUPPLIER = UUID.randomUUID();
    private static final UUID LINE_1 = UUID.randomUUID();
    private static final UUID LINE_2 = UUID.randomUUID();
    private static final UUID ITEM_1 = UUID.randomUUID();
    private static final UUID ITEM_2 = UUID.randomUUID();

    private final SupplierItemRepository supplierItems = mock(SupplierItemRepository.class);
    private final ProductService products = mock(ProductService.class);
    private final AmendmentLineChecks checks = new AmendmentLineChecks(supplierItems, products);

    private static PoLine line(UUID id, int no, UUID item, String sku, int quantity, PackingUnit unit) {
        return new PoLine(id, no, item, new Sku(sku), "EACH", sku, quantity, 0, Money.vnd(1000), BigDecimal.ZERO,
                PoLineStatus.OPEN, unit);
    }

    private static PurchaseOrder order(PoLine... lines) {
        return new PurchaseOrder(new PurchaseOrder.State(PurchaseOrderId.newId(), "PO-20261001-000001",
                PurchaseOrder.Type.STANDARD, null, SUPPLIER, UUID.randomUUID(), PurchaseOrderStatus.APPROVED,
                Money.VND, List.of(lines), ORDER_DATE, EXPECTED, null, 30, 7, 1, UUID.randomUUID(), null,
                UUID.randomUUID(), NOW, UUID.randomUUID(), NOW, false, null, null, null, null, null, null, null,
                SupplierConfirmationStatus.NOT_SENT, null, null, null, 3, NOW, "buyer", NOW, "buyer"));
    }

    private static PurchaseOrder twoLines() {
        return order(line(LINE_1, 1, ITEM_1, "CUP-12OZ", 100, null), line(LINE_2, 2, ITEM_2, "CUP-16OZ", 100, null));
    }

    private static PurchaseOrder.AmendmentLine qty(UUID id, int quantity) {
        return new PurchaseOrder.AmendmentLine(id, quantity, null, null, null);
    }

    private static PurchaseOrder.ProposedAmendment propose(PurchaseOrder order, PurchaseOrder.AmendmentLine... lines) {
        return order.proposeAmendment(new PurchaseOrder.AmendmentRequest(EXPECTED, null, "Because",
                List.of(lines)));
    }

    private static SupplierItem listed(UUID item, int moq, int packSize) {
        return SupplierItem.create(SUPPLIER, item,
                SupplierItemTerms.of(null, new BigDecimal("1000"), Money.VND, moq, packSize, null));
    }

    private void catalog(Map<UUID, SupplierItem> items) {
        when(supplierItems.listedForShare(any(), anyCollection())).thenReturn(items);
    }

    private static void assertRefused(Runnable action, ErrorCode code) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(BusinessException.class,
                e -> assertThat(e.errorCode()).isEqualTo(code));
    }

    @Test
    void termsCheckedOnlyForChangedQuantities() {
        var order = twoLines();
        catalog(Map.of());   // the supplier lists nothing any more

        var repriced = propose(order, new PurchaseOrder.AmendmentLine(LINE_1, 100, new BigDecimal("900"), null, null),
                qty(LINE_2, 100));
        checks.require(order, repriced);   // no quantity changes: the catalog is not even asked
        verify(supplierItems, never()).listedForShare(any(), anyCollection());

        var decreased = propose(order, new PurchaseOrder.AmendmentLine(LINE_1, 80, new BigDecimal("900"), null, null),
                qty(LINE_2, 100));
        checks.require(order, decreased);   // a decrease of an unlisted SKU has no terms to meet

        var increased = propose(order, new PurchaseOrder.AmendmentLine(LINE_1, 120, new BigDecimal("900"), null, null),
                qty(LINE_2, 100));
        assertRefused(() -> checks.require(order, increased), ErrorCode.SUPPLIER_ITEM_NOT_LISTED);
    }

    @Test
    void belowMoqAndNotPackMultipleReportedPerLine() {
        var order = twoLines();
        catalog(Map.of(ITEM_1, listed(ITEM_1, 50, 10), ITEM_2, listed(ITEM_2, 10, 10)));

        var proposal = propose(order, qty(LINE_1, 40), qty(LINE_2, 95));

        assertThatThrownBy(() -> checks.require(order, proposal)).isInstanceOfSatisfying(BusinessException.class, e -> {
            assertThat(e.errorCode()).isEqualTo(ErrorCode.SUPPLIER_TERMS_VIOLATED);
            assertThat(e.fieldErrors()).extracting(FieldError::field, FieldError::code).containsExactly(
                    tuple("lines[0].quantityOrdered",
                            ErrorCode.PO_LINE_BELOW_MOQ.name()),
                    tuple("lines[1].quantityOrdered",
                            ErrorCode.PO_LINE_NOT_PACK_MULTIPLE.name()));
        });
    }

    @Test
    void increaseOfDiscontinuedSkuRefusedDecreaseAllowed() {
        var order = twoLines();
        catalog(Map.of(ITEM_1, listed(ITEM_1, 1, 1), ITEM_2, listed(ITEM_2, 1, 1)));
        when(products.purchasability(anyCollection())).thenReturn(Map.of("CUP-12OZ", Purchasability.PRODUCT_DISCONTINUED,
                "CUP-16OZ", Purchasability.PRODUCT_DISCONTINUED));

        var increase = propose(order, qty(LINE_1, 120), qty(LINE_2, 100));
        assertThatThrownBy(() -> checks.require(order, increase)).isInstanceOfSatisfying(BusinessException.class, e -> {
            assertThat(e.errorCode()).isEqualTo(ErrorCode.SKU_NOT_PURCHASABLE);
            assertThat(e.fieldErrors()).extracting(FieldError::field, FieldError::code).containsExactly(
                    tuple("lines[0].sku", "PRODUCT_DISCONTINUED"));
        });

        var decrease = propose(order, qty(LINE_1, 80), qty(LINE_2, 100));
        checks.require(order, decrease);   // does not throw

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Collection<String>> asked = ArgumentCaptor.forClass(Collection.class);
        verify(products, times(1)).purchasability(asked.capture());   // the decrease never asked
        assertThat(asked.getValue()).containsExactly("CUP-12OZ");
    }

    @Test
    void packingUnitQuantityMustBeWhole() {
        var unit = new PackingUnit(UUID.randomUUID(), "CARTON", 12, 5);   // 5 cartons of 12 = 60
        var order = order(line(LINE_1, 1, ITEM_1, "CUP-12OZ", 60, unit));

        assertRefused(() -> propose(order, qty(LINE_1, 30)), ErrorCode.PO_LINE_UNIT_NOT_MULTIPLE);

        var whole = propose(order, qty(LINE_1, 36));   // 3 cartons
        assertThat(whole.kept().get(0).after().packingUnit().count()).isEqualTo(3);
    }

    @Test
    void catalogReadOnceForAllChangedLines() {
        var order = twoLines();
        catalog(Map.of(ITEM_1, listed(ITEM_1, 1, 1), ITEM_2, listed(ITEM_2, 1, 1)));

        checks.require(order, propose(order, qty(LINE_1, 80), qty(LINE_2, 90)));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Collection<UUID>> asked = ArgumentCaptor.forClass(Collection.class);
        verify(supplierItems, times(1)).listedForShare(any(), asked.capture());
        assertThat(asked.getValue()).containsExactlyInAnyOrder(ITEM_1, ITEM_2);
    }
}
