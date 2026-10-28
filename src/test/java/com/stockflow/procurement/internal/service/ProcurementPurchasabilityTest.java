package com.stockflow.procurement.internal.service;

import com.stockflow.common.domain.Money;
import com.stockflow.common.domain.Sku;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.security.CurrentUserProvider;
import com.stockflow.inventory.api.InventoryControlItem;
import com.stockflow.inventory.api.InventoryControlService;
import com.stockflow.inventory.api.InventoryItemPolicy;
import com.stockflow.inventory.api.InventoryService;
import com.stockflow.notification.api.NotificationService;
import com.stockflow.procurement.api.CreatePOLineCommand;
import com.stockflow.procurement.api.CreatePurchaseOrderCommand;
import com.stockflow.procurement.api.PurchaseOrderSummary;
import com.stockflow.procurement.api.RevisePOLineCommand;
import com.stockflow.procurement.api.RevisePurchaseOrderCommand;
import com.stockflow.procurement.internal.domain.CatalogSupplier;
import com.stockflow.procurement.internal.domain.PurchaseOrder;
import com.stockflow.procurement.internal.domain.PurchaseOrderRepository;
import com.stockflow.procurement.internal.domain.PurchaseOrderStatus;
import com.stockflow.procurement.internal.domain.Supplier;
import com.stockflow.procurement.internal.domain.SupplierCommunicationChannel;
import com.stockflow.procurement.internal.domain.SupplierDetails;
import com.stockflow.procurement.internal.domain.SupplierItem;
import com.stockflow.procurement.internal.domain.SupplierItemRepository;
import com.stockflow.procurement.internal.domain.SupplierItemTerms;
import com.stockflow.procurement.internal.domain.SupplierRepository;
import com.stockflow.procurement.internal.domain.SupplierStatus;
import com.stockflow.procurement.internal.repository.PoDeliveryDecisionRepository;
import com.stockflow.procurement.internal.repository.PurchaseOrderSearchRepository;
import com.stockflow.product.api.ProductService;
import com.stockflow.product.api.Purchasability;
import com.stockflow.warehouse.api.WarehouseService;
import com.stockflow.warehouse.api.WarehouseView;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * kltn-docs 02 BR-01 (SCRUM-461): a new purchase order line takes only an active SKU. One call to the product
 * module for the whole request, one refusal naming every line at fault by index with the reason as the field
 * code, in the same places everywhere a line enters a standard order: create, edit of a draft, submit and approve.
 * A subcontract order is never asked.
 */
class ProcurementPurchasabilityTest {

    private final UUID supplierId = UUID.randomUUID();
    private final UUID warehouseId = UUID.randomUUID();
    private final SupplierRepository suppliers = mock(SupplierRepository.class);
    private final PurchaseOrderRepository orders = mock(PurchaseOrderRepository.class);
    private final PurchaseOrderSearchRepository search = mock(PurchaseOrderSearchRepository.class);
    private final InventoryService inventory = mock(InventoryService.class);
    private final InventoryControlService inventoryControl = mock(InventoryControlService.class);
    private final WarehouseService warehouses = mock(WarehouseService.class);
    private final ProductService products = mock(ProductService.class);
    private final SupplierItemRepository supplierItems = mock(SupplierItemRepository.class);
    private ProcurementServiceImpl service;

    @BeforeEach
    void setUp() {
        var supplier = new Supplier(supplierId, new SupplierDetails("SUP", "Supplier", null, "s@example.com", null, null,
                SupplierStatus.ACTIVE, 30, 7, SupplierCommunicationChannel.EMAIL, null, null, false, null), null, null);
        when(suppliers.findById(supplierId)).thenReturn(Optional.of(supplier));
        when(suppliers.findByIdForUpdate(supplierId)).thenReturn(Optional.of(supplier));
        when(orders.save(any())).thenAnswer(call -> call.getArgument(0));
        when(orders.nextPoNumber(any())).thenReturn("PO-TEST");
        when(search.summary(any(), any(Boolean.class))).thenReturn(Optional.of(mock(PurchaseOrderSummary.class)));
        when(warehouses.findWarehouse(warehouseId))
                .thenReturn(Optional.of(new WarehouseView(warehouseId, "HCM", "Kho HCM", "HCM", true)));
        // One inventory item per SKU, as the database has it.
        when(inventory.itemPolicy(any(Sku.class))).thenAnswer(call -> {
            Sku sku = call.getArgument(0);
            return Optional.of(new InventoryItemPolicy(UUID.nameUUIDFromBytes(sku.code().getBytes()), sku.code(),
                    false, false, false));
        });
        when(inventoryControl.item(any())).thenAnswer(call ->
                new InventoryControlItem(((Sku) call.getArgument(0)).code(), "EACH", 0, null));
        // The supplier lists every SKU asked for, moq 1 in packs of 1: the catalog is not what these tests are about.
        when(supplierItems.findSupplier(supplierId)).thenReturn(Optional.of(
                new CatalogSupplier(supplierId, "SUP", "Supplier", SupplierStatus.ACTIVE, Money.VND, 7)));
        when(supplierItems.listedForShare(any(), anyCollection())).thenAnswer(call -> {
            Collection<UUID> items = call.getArgument(1);
            return items.stream().collect(Collectors.toMap(Function.identity(), item -> new SupplierItem(
                    UUID.randomUUID(), supplierId, item, SupplierItemTerms.stored(null, Money.vnd(100), 1, 1, null),
                     0, null, null)));
        });
        service = new ProcurementServiceImpl(orders, search, null,
                Clock.fixed(Instant.parse("2026-10-14T03:00:00Z"), ZoneOffset.UTC), suppliers,
                mock(ApplicationEventPublisher.class), mock(NotificationService.class),
                mock(PoDeliveryDecisionRepository.class), null, products, inventory, inventoryControl, warehouses,
                mock(CurrentUserProvider.class), supplierItems, noLimits());
    }

    private CreatePurchaseOrderCommand create(String... skus) {
        return new CreatePurchaseOrderCommand(supplierId, warehouseId, "VND", null, null,
                java.util.Arrays.stream(skus).map(sku -> new CreatePOLineCommand(sku, "Line " + sku, 1, BigDecimal.TEN)).toList());
    }

    private PurchaseOrder draftOf(String... skus) {
        var lines = new java.util.ArrayList<com.stockflow.procurement.internal.domain.PoLine>();
        for (String sku : skus) {
            lines.add(PurchaseOrder.line(lines.size() + 1, UUID.nameUUIDFromBytes(sku.getBytes()), new Sku(sku), "EACH",
                    "Line " + sku, 1, Money.vnd(100), null));
        }
        return PurchaseOrder.draft("PO-D", supplierId, warehouseId, Money.VND, lines, LocalDate.parse("2026-10-14"),
                LocalDate.parse("2026-10-20"), null, 30, 7);
    }

    /** Nobody has an approval limit: every submit waits for approval, which is what these tests were written against. */
    private static ApprovalLimits noLimits() {
        ApprovalLimits limits = mock(ApprovalLimits.class);
        when(limits.limitOf(any(), any(), any())).thenReturn(java.util.Optional.empty());
        return limits;
    }

    private void verdicts(Object... skuAndVerdict) {
        var map = new java.util.HashMap<String, Purchasability>();
        for (int i = 0; i + 1 < skuAndVerdict.length; i += 2) {
            map.put((String) skuAndVerdict[i], (Purchasability) skuAndVerdict[i + 1]);
        }
        when(products.purchasability(anyCollection())).thenReturn(map);
    }

    private static BusinessException refusal(Throwable thrown) {
        assertThat(thrown).isInstanceOf(BusinessException.class);
        return (BusinessException) thrown;
    }

    @Test
    @DisplayName("create: every line at fault is named by its index with the reason as the code, in one refusal")
    void createNamesEveryLineAtFault() {
        verdicts("SKU-AAA", Purchasability.PURCHASABLE, "SKU-BBB", Purchasability.PRODUCT_DISCONTINUED,
                "SKU-CCC", Purchasability.PRODUCT_INACTIVE, "SKU-DDD", Purchasability.VARIANT_NOT_ACTIVE,
                "SKU-EEE", Purchasability.PRODUCT_NOT_APPROVED);

        var thrown = refusal(org.assertj.core.api.Assertions.catchThrowable(() ->
                service.createPurchaseOrder(create("SKU-AAA", "SKU-BBB", "SKU-CCC", "SKU-DDD", "SKU-EEE"))));

        assertThat(thrown.errorCode()).isEqualTo(ErrorCode.SKU_NOT_PURCHASABLE);
        assertThat(thrown.fieldErrors()).extracting(f -> f.field() + ":" + f.code()).containsExactly(
                "lines[1].sku:PRODUCT_DISCONTINUED", "lines[2].sku:PRODUCT_INACTIVE", "lines[3].sku:VARIANT_NOT_ACTIVE",
                "lines[4].sku:PRODUCT_NOT_APPROVED");
        verify(orders, never()).save(any());
    }

    @Test
    @DisplayName("create: the product module is asked once for the whole request, with every SKU")
    void createAsksOnce() {
        verdicts("SKU-AAA", Purchasability.PURCHASABLE, "SKU-BBB", Purchasability.PURCHASABLE);

        service.createPurchaseOrder(create("SKU-AAA", "SKU-BBB"));

        verify(products, times(1)).purchasability(List.of("SKU-AAA", "SKU-BBB"));
        verify(orders).save(any());
    }

    @Test
    @DisplayName("create: a SKU the product module does not mention is not refused by procurement")
    void anUnmentionedSkuIsNotRefused() {
        verdicts();

        assertThatCode(() -> service.createPurchaseOrder(create("SKU-AAA"))).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("create: a line with no inventory item is still INVENTORY_ITEM_NOT_FOUND, asked before purchasability")
    void inventoryItemIsCheckedFirst() {
        when(inventory.itemPolicy(any(Sku.class))).thenReturn(Optional.empty());
        verdicts("SKU-AAA", Purchasability.PRODUCT_DISCONTINUED);

        assertThatThrownBy(() -> service.createPurchaseOrder(create("SKU-AAA")))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.INVENTORY_ITEM_NOT_FOUND));
        verify(products, never()).purchasability(anyCollection());
    }

    @Test
    @DisplayName("edit of a draft: the same check on the lines as they will be")
    void reviseDraftChecksTheLines() {
        PurchaseOrder draft = draftOf("SKU-AAA");
        when(orders.findByIdForUpdate(any())).thenReturn(Optional.of(draft));
        verdicts("SKU-AAA", Purchasability.PURCHASABLE, "SKU-BBB", Purchasability.PRODUCT_INACTIVE);

        var command = new RevisePurchaseOrderCommand(draft.version(), warehouseId, LocalDate.parse("2026-10-20"), null,
                List.of(new RevisePOLineCommand(draft.lines().getFirst().id(), "SKU-AAA", "Line", 1, BigDecimal.TEN, null),
                        new RevisePOLineCommand(null, "SKU-BBB", "Line", 1, BigDecimal.TEN, null)));

        var thrown = refusal(org.assertj.core.api.Assertions.catchThrowable(
                () -> service.reviseDraft(draft.id().value(), command)));

        assertThat(thrown.errorCode()).isEqualTo(ErrorCode.SKU_NOT_PURCHASABLE);
        assertThat(thrown.fieldErrors()).singleElement().satisfies(f -> {
            assertThat(f.field()).isEqualTo("lines[1].sku");
            assertThat(f.code()).isEqualTo("PRODUCT_INACTIVE");
        });
        assertThat(draft.lines()).hasSize(1);
    }

    @Test
    @DisplayName("submit: a draft that holds a SKU discontinued meanwhile is refused before a revision is written")
    void submitRefusesAStaleLine() {
        PurchaseOrder draft = draftOf("SKU-AAA", "SKU-BBB");
        when(orders.findByIdForUpdate(any())).thenReturn(Optional.of(draft));
        verdicts("SKU-AAA", Purchasability.PURCHASABLE, "SKU-BBB", Purchasability.PRODUCT_DISCONTINUED);

        var thrown = refusal(org.assertj.core.api.Assertions.catchThrowable(
                () -> service.submit(draft.id().value(), UUID.randomUUID())));

        assertThat(thrown.errorCode()).isEqualTo(ErrorCode.SKU_NOT_PURCHASABLE);
        assertThat(thrown.fieldErrors()).singleElement().satisfies(f -> {
            assertThat(f.field()).isEqualTo("lines[1].sku");
            assertThat(f.code()).isEqualTo("PRODUCT_DISCONTINUED");
        });
        assertThat(draft.status()).isEqualTo(PurchaseOrderStatus.DRAFT);
        verify(orders, never()).recordRevision(any(), org.mockito.ArgumentMatchers.anyLong(), any(), any());
    }

    @Test
    @DisplayName("submit: an order of active SKUs goes through")
    void submitAcceptsActiveLines() {
        PurchaseOrder draft = draftOf("SKU-AAA");
        when(orders.findByIdForUpdate(any())).thenReturn(Optional.of(draft));
        when(orders.recordRevision(any(), org.mockito.ArgumentMatchers.anyLong(), any(), any())).thenReturn(UUID.randomUUID());
        verdicts("SKU-AAA", Purchasability.PURCHASABLE);

        service.submit(draft.id().value(), UUID.randomUUID());

        assertThat(draft.status()).isEqualTo(PurchaseOrderStatus.PENDING_APPROVAL);
    }

    @Test
    @DisplayName("approve: a pending order whose SKU was discontinued while it waited is refused and stays pending")
    void approveRefusesAStaleLine() {
        PurchaseOrder order = draftOf("SKU-AAA");
        order.submit(UUID.randomUUID(), UUID.randomUUID(), 0, Instant.parse("2026-10-14T03:00:00Z"));
        when(orders.findByIdForUpdate(any())).thenReturn(Optional.of(order));
        verdicts("SKU-AAA", Purchasability.PRODUCT_DISCONTINUED);

        var thrown = refusal(org.assertj.core.api.Assertions.catchThrowable(
                () -> service.approve(order.id().value(), UUID.randomUUID())));

        assertThat(thrown.errorCode()).isEqualTo(ErrorCode.SKU_NOT_PURCHASABLE);
        assertThat(thrown.fieldErrors()).singleElement().satisfies(f -> assertThat(f.field()).isEqualTo("lines[0].sku"));
        assertThat(order.status()).isEqualTo(PurchaseOrderStatus.PENDING_APPROVAL);
    }

    @Test
    @DisplayName("approve: the status error still wins for an order that is not waiting for approval")
    void approveKeepsItsOwnTransitionError() {
        PurchaseOrder draft = draftOf("SKU-AAA");
        when(orders.findByIdForUpdate(any())).thenReturn(Optional.of(draft));
        verdicts("SKU-AAA", Purchasability.PRODUCT_DISCONTINUED);

        assertThatThrownBy(() -> service.approve(draft.id().value(), UUID.randomUUID()))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.INVALID_PURCHASE_ORDER_TRANSITION));
        verify(products, never()).purchasability(anyCollection());
    }

    @Test
    @DisplayName("a subcontract order is never asked: its line follows a production order already running")
    void subcontractOrdersAreNotAsked() {
        var order = PurchaseOrder.subcontract("PO-S", UUID.randomUUID(), supplierId, warehouseId, Money.VND,
                PurchaseOrder.line(1, UUID.nameUUIDFromBytes("SKU-AAA".getBytes()), new Sku("SKU-AAA"), "EACH", "Print", 10,
                        Money.vnd(100), null), LocalDate.parse("2026-10-14"), LocalDate.parse("2026-10-20"), 30, 7);
        when(orders.findByIdForUpdate(any())).thenReturn(Optional.of(order));
        when(orders.recordRevision(any(), org.mockito.ArgumentMatchers.anyLong(), any(), any())).thenReturn(UUID.randomUUID());
        verdicts("SKU-AAA", Purchasability.PRODUCT_INACTIVE);

        service.submit(order.id().value(), UUID.randomUUID());

        assertThat(order.status()).isEqualTo(PurchaseOrderStatus.PENDING_APPROVAL);
        verify(products, never()).purchasability(anyCollection());
    }
}
