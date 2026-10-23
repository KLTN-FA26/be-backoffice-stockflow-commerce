package com.stockflow.procurement.internal.service;

import com.stockflow.common.api.FieldError;
import com.stockflow.common.api.PageResponse;
import com.stockflow.common.domain.Money;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.inventory.api.InventoryItemPolicy;
import com.stockflow.inventory.api.InventoryService;
import com.stockflow.inventory.api.ItemPurchasingService;
import com.stockflow.procurement.api.SaveSupplierItemCommand;
import com.stockflow.procurement.api.SkuSupplierSummary;
import com.stockflow.procurement.api.SupplierItemSummary;
import com.stockflow.procurement.internal.domain.CatalogSupplier;
import com.stockflow.procurement.internal.domain.PriceChange;
import com.stockflow.procurement.internal.domain.SupplierItem;
import com.stockflow.procurement.internal.domain.SupplierItemRepository;
import com.stockflow.procurement.internal.domain.SupplierItemRepository.SkuSupplierRow;
import com.stockflow.procurement.internal.domain.SupplierItemTerms;
import com.stockflow.procurement.internal.domain.SupplierStatus;
import com.stockflow.product.api.ProductService;
import com.stockflow.product.api.Purchasability;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Currency;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** kltn-docs warehouse/01 §3, 02 BR-01: the supplier catalog (price, MOQ, pack size, lead time, preferred supplier). */
class SupplierItemServiceImplTest {

    private static final Instant NOW = Instant.parse("2026-10-22T03:00:00Z");
    private static final Currency VND = Currency.getInstance("VND");

    private final SupplierItemRepository items = mock(SupplierItemRepository.class);
    private final InventoryService inventory = mock(InventoryService.class);
    private final ItemPurchasingService purchasing = mock(ItemPurchasingService.class);
    private final ProductService products = mock(ProductService.class);
    private final SupplierItemServiceImpl service = new SupplierItemServiceImpl(items, inventory, purchasing, products,
            Clock.fixed(NOW, ZoneOffset.UTC));

    private final UUID supplierId = UUID.randomUUID();
    private final UUID inventoryItemId = UUID.randomUUID();
    private final CatalogSupplier supplier = new CatalogSupplier(supplierId, "ACME", "ACME", SupplierStatus.ACTIVE, VND,
            10);

    @BeforeEach
    void defaults() {
        when(items.supplierForShare(supplierId)).thenReturn(Optional.of(supplier));
        when(items.findSupplier(supplierId)).thenReturn(Optional.of(supplier));
        when(inventory.itemPolicy(new com.stockflow.common.domain.Sku("CUP-01")))
                .thenReturn(Optional.of(new InventoryItemPolicy(inventoryItemId, "CUP-01", false, false, false)));
        when(inventory.itemPolicy(inventoryItemId))
                .thenReturn(Optional.of(new InventoryItemPolicy(inventoryItemId, "CUP-01", false, false, false)));
        when(products.purchasability(anyCollection())).thenReturn(Map.of("CUP-01", Purchasability.PURCHASABLE));
        when(purchasing.defaultSuppliers(anyCollection())).thenReturn(Map.of());
        when(items.save(any(), any(), any())).thenAnswer(call -> call.getArgument(0));
    }

    private SaveSupplierItemCommand command(String price, int moq, int pack, Integer lead, boolean preferred,
                                            Long version) {
        return new SaveSupplierItemCommand("cup-01", " SUP-CUP ", new BigDecimal(price), moq, pack, lead, preferred,
                version);
    }

    private SupplierItem stored(String price, long version) {
        return new SupplierItem(UUID.randomUUID(), supplierId, inventoryItemId,
                SupplierItemTerms.stored("X", new Money(new BigDecimal(price), VND), 10, 5, null), version, NOW, NOW);
    }

    private static BusinessException refused(Runnable call) {
        BusinessException thrown = catchThrowableOfType(BusinessException.class, call::run);
        assertThat(thrown).isNotNull();
        return thrown;
    }

    // ------------------------------------------------------------------ create

    @Test
    @DisplayName("create: the row is priced in the supplier's currency; the default lead time falls back to the supplier's")
    void create() {
        SupplierItemSummary created = service.create(supplierId, command("1500", 100, 50, null, false, null));

        assertThat(created.sku()).isEqualTo("CUP-01");
        assertThat(created.supplierSkuCode()).isEqualTo("SUP-CUP");
        assertThat(created.currency()).isEqualTo("VND");
        assertThat(created.price()).isEqualByComparingTo("1500");
        assertThat(created.leadTimeDays()).isNull();
        assertThat(created.effectiveLeadTimeDays()).isEqualTo(10);
        assertThat(created.preferred()).isFalse();
        verify(purchasing, never()).assignDefaultSupplier(any(), any());
    }

    @Test
    @DisplayName("create: an item's own lead time wins over the supplier's")
    void ownLeadTime() {
        assertThat(service.create(supplierId, command("1500", 100, 50, 3, false, null)).effectiveLeadTimeDays())
                .isEqualTo(3);
    }

    @Test
    @DisplayName("create: 'preferred' makes the supplier the item's default supplier")
    void createPreferred() {
        when(purchasing.defaultSuppliers(anyCollection())).thenReturn(Map.of(inventoryItemId, supplierId));
        SupplierItemSummary created = service.create(supplierId, command("1500", 100, 50, null, true, null));
        verify(purchasing).assignDefaultSupplier(inventoryItemId, supplierId);
        assertThat(created.preferred()).isTrue();
    }

    @Test
    @DisplayName("create: an unknown supplier is SUPPLIER_NOT_FOUND; an inactive one is SUPPLIER_INACTIVE")
    void supplierGuards() {
        UUID missing = UUID.randomUUID();
        when(items.supplierForShare(missing)).thenReturn(Optional.empty());
        assertThat(refused(() -> service.create(missing, command("1", 1, 1, null, false, null))).errorCode())
                .isEqualTo(ErrorCode.SUPPLIER_NOT_FOUND);

        when(items.supplierForShare(supplierId)).thenReturn(Optional.of(new CatalogSupplier(supplierId, "ACME", "ACME",
                SupplierStatus.INACTIVE, VND, 10)));
        assertThat(refused(() -> service.create(supplierId, command("1", 1, 1, null, false, null))).errorCode())
                .isEqualTo(ErrorCode.SUPPLIER_INACTIVE);
        verify(items, never()).save(any(), any(), any());
    }

    @Test
    @DisplayName("create: a malformed SKU is a 400 on field 'sku' (SUPPLIER_ITEM_SKU_INVALID); an unknown SKU is INVENTORY_ITEM_NOT_FOUND")
    void skuGuards() {
        var bad = new SaveSupplierItemCommand("not a sku!", null, BigDecimal.ONE, 1, 1, null, false, null);
        BusinessException malformed = refused(() -> service.create(supplierId, bad));
        assertThat(malformed.errorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED);
        assertThat(malformed.fieldErrors()).extracting(FieldError::field).containsExactly("sku");
        assertThat(malformed.fieldErrors()).extracting(FieldError::code).containsExactly("SUPPLIER_ITEM_SKU_INVALID");

        var unknown = new SaveSupplierItemCommand("NOPE-01", null, BigDecimal.ONE, 1, 1, null, false, null);
        assertThat(refused(() -> service.create(supplierId, unknown)).errorCode())
                .isEqualTo(ErrorCode.INVENTORY_ITEM_NOT_FOUND);
    }

    @Test
    @DisplayName("02 BR-01: a SKU that is not active for purchasing is SKU_NOT_PURCHASABLE, with the reason on 'sku'")
    void notPurchasable() {
        when(products.purchasability(anyCollection())).thenReturn(Map.of("CUP-01", Purchasability.PRODUCT_DISCONTINUED));
        BusinessException e = refused(() -> service.create(supplierId, command("1500", 100, 50, null, false, null)));
        assertThat(e.errorCode()).isEqualTo(ErrorCode.SKU_NOT_PURCHASABLE);
        assertThat(e.fieldErrors()).extracting(FieldError::code).containsExactly("PRODUCT_DISCONTINUED");
        verify(items, never()).save(any(), any(), any());
    }

    @Test
    @DisplayName("create: the terms are held to the catalog rules (price > 0, MOQ >= 1, pack >= 1, MOQ a multiple of the "
            + "pack, lead time 0..365, VND has no decimals)")
    void termsAreChecked() {
        assertThat(codes(command("0", 10, 5, null, false, null))).containsExactly("SUPPLIER_ITEM_PRICE_INVALID");
        assertThat(codes(command("-5", 10, 5, null, false, null))).containsExactly("SUPPLIER_ITEM_PRICE_INVALID");
        assertThat(codes(command("1500.5", 10, 5, null, false, null))).containsExactly("SUPPLIER_ITEM_PRICE_INVALID");
        assertThat(codes(command("1500", 0, 1, null, false, null))).containsExactly("SUPPLIER_ITEM_MOQ_INVALID");
        assertThat(codes(command("1500", 10, 0, null, false, null))).containsExactly("SUPPLIER_ITEM_PACK_SIZE_INVALID");
        assertThat(codes(command("1500", 12, 5, null, false, null)))
                .containsExactly("SUPPLIER_ITEM_MOQ_NOT_PACK_MULTIPLE");
        assertThat(codes(command("1500", 10, 5, 366, false, null))).containsExactly("SUPPLIER_ITEM_LEAD_TIME_INVALID");
        assertThat(codes(command("1500", 10, 5, -1, false, null))).containsExactly("SUPPLIER_ITEM_LEAD_TIME_INVALID");
        assertThat(service.create(supplierId, command("1500", 10, 5, 365, false, null)).leadTimeDays()).isEqualTo(365);
        assertThat(service.create(supplierId, command("1500", 10, 5, 0, false, null)).leadTimeDays()).isZero();
    }

    private List<String> codes(SaveSupplierItemCommand command) {
        BusinessException e = refused(() -> service.create(supplierId, command));
        assertThat(e.errorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED);
        return e.fieldErrors().stream().map(FieldError::code).toList();
    }

    // ------------------------------------------------------------------ update

    @Test
    @DisplayName("update: the version is required (400 on 'version'), a stale one is OPTIMISTIC_LOCK, another supplier's row is NOT_FOUND")
    void updateGuards() {
        SupplierItem item = stored("1500", 3);
        when(items.findByIdForUpdate(item.id())).thenReturn(Optional.of(item));

        BusinessException noVersion = refused(() -> service.update(supplierId, item.id(), command("1500", 10, 5, null,
                false, null)));
        assertThat(noVersion.errorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED);
        assertThat(noVersion.fieldErrors()).extracting(FieldError::field).containsExactly("version");

        assertThat(refused(() -> service.update(supplierId, item.id(), command("1500", 10, 5, null, false, 2L)))
                .errorCode()).isEqualTo(ErrorCode.OPTIMISTIC_LOCK);

        UUID otherSupplier = UUID.randomUUID();
        when(items.supplierForShare(otherSupplier)).thenReturn(Optional.of(new CatalogSupplier(otherSupplier, "O", "O",
                SupplierStatus.ACTIVE, VND, null)));
        assertThat(refused(() -> service.update(otherSupplier, item.id(), command("1500", 10, 5, null, false, 3L)))
                .errorCode()).isEqualTo(ErrorCode.SUPPLIER_ITEM_NOT_FOUND);

        UUID missing = UUID.randomUUID();
        when(items.findByIdForUpdate(missing)).thenReturn(Optional.empty());
        assertThat(refused(() -> service.update(supplierId, missing, command("1500", 10, 5, null, false, 3L)))
                .errorCode()).isEqualTo(ErrorCode.SUPPLIER_ITEM_NOT_FOUND);
    }

    @Test
    @DisplayName("update: a changed price is handed to the repository as a price change (history); the same price is not one "
            + "(10.00 vs 10.0)")
    void priceChange() {
        SupplierItem item = stored("1500", 0);
        when(items.findByIdForUpdate(item.id())).thenReturn(Optional.of(item));

        service.update(supplierId, item.id(), command("1500.00", 10, 5, null, false, 0L));
        ArgumentCaptor<Optional<PriceChange>> same = ArgumentCaptor.forClass(Optional.class);
        verify(items).save(any(), same.capture(), any());
        assertThat(same.getValue()).isEmpty();

        service.update(supplierId, item.id(), command("1800", 10, 5, null, false, 0L));
        ArgumentCaptor<Optional<PriceChange>> changed = ArgumentCaptor.forClass(Optional.class);
        verify(items, org.mockito.Mockito.times(2)).save(any(), changed.capture(), any());
        assertThat(changed.getValue()).isPresent();
        assertThat(changed.getValue().get().from().amount()).isEqualByComparingTo("1500");
        assertThat(changed.getValue().get().to().amount()).isEqualByComparingTo("1800");
    }

    @Test
    @DisplayName("update: preferred sets the item's default supplier, not preferred clears it (only if it is this supplier)")
    void updatePreferred() {
        SupplierItem item = stored("1500", 0);
        when(items.findByIdForUpdate(item.id())).thenReturn(Optional.of(item));

        service.update(supplierId, item.id(), command("1500", 10, 5, null, true, 0L));
        verify(purchasing).assignDefaultSupplier(inventoryItemId, supplierId);

        service.update(supplierId, item.id(), command("1500", 10, 5, null, false, 0L));
        verify(purchasing).clearDefaultSupplier(inventoryItemId, supplierId);
    }

    // ------------------------------------------------------------------ delete

    @Test
    @DisplayName("delete: a row an open purchase order still orders from is SUPPLIER_ITEM_IN_USE; otherwise it is removed")
    void delete() {
        SupplierItem item = stored("1500", 4);
        when(items.findByIdForUpdate(item.id())).thenReturn(Optional.of(item));
        when(items.usedByOpenPurchaseOrder(supplierId, inventoryItemId)).thenReturn(true);
        assertThat(refused(() -> service.delete(supplierId, item.id(), 4)).errorCode())
                .isEqualTo(ErrorCode.SUPPLIER_ITEM_IN_USE);
        verify(items, never()).delete(any());

        when(items.usedByOpenPurchaseOrder(supplierId, inventoryItemId)).thenReturn(false);
        assertThat(refused(() -> service.delete(supplierId, item.id(), 1)).errorCode())
                .isEqualTo(ErrorCode.OPTIMISTIC_LOCK);
        service.delete(supplierId, item.id(), 4);
        verify(items).delete(item);
    }

    @Test
    @DisplayName("delete: an INACTIVE supplier's rows can still be cleaned up (only existence is required)")
    void deleteForInactiveSupplier() {
        when(items.supplierForShare(supplierId)).thenReturn(Optional.of(new CatalogSupplier(supplierId, "ACME", "ACME",
                SupplierStatus.INACTIVE, VND, 10)));
        SupplierItem item = stored("1500", 0);
        when(items.findByIdForUpdate(item.id())).thenReturn(Optional.of(item));
        service.delete(supplierId, item.id(), 0);
        verify(items).delete(item);
    }

    // ------------------------------------------------------------------ reads

    @Test
    @DisplayName("list: an unknown supplier is SUPPLIER_NOT_FOUND; rows say whether the supplier is the item's default")
    void list() {
        UUID missing = UUID.randomUUID();
        when(items.findSupplier(missing)).thenReturn(Optional.empty());
        assertThat(refused(() -> service.list(missing, 0, 20, null)).errorCode()).isEqualTo(ErrorCode.SUPPLIER_NOT_FOUND);

        SupplierItem item = stored("1500", 0);
        when(items.listBySupplier(supplierId, 0, 20, null)).thenReturn(PageResponse.of(List.of(item), 0, 20, 1));
        when(inventory.itemPolicies(anyCollection())).thenReturn(Map.of(inventoryItemId,
                new InventoryItemPolicy(inventoryItemId, "CUP-01", false, false, false)));
        when(purchasing.defaultSuppliers(anyCollection())).thenReturn(Map.of(inventoryItemId, supplierId));

        PageResponse<SupplierItemSummary> page = service.list(supplierId, 0, 20, null);

        assertThat(page.items()).hasSize(1);
        assertThat(page.items().get(0).preferred()).isTrue();
        assertThat(page.items().get(0).sku()).isEqualTo("CUP-01");
    }

    @Test
    @DisplayName("suppliersOf: the item's default supplier comes first, the others keep the repository's order; "
            + "the effective lead time falls back per supplier")
    void suppliersOf() {
        UUID preferredId = UUID.randomUUID();
        CatalogSupplier a = new CatalogSupplier(UUID.randomUUID(), "A", "Alpha", SupplierStatus.ACTIVE, VND, 4);
        CatalogSupplier preferred = new CatalogSupplier(preferredId, "P", "Zeta", SupplierStatus.ACTIVE, VND, null);
        CatalogSupplier b = new CatalogSupplier(UUID.randomUUID(), "B", "Beta", SupplierStatus.INACTIVE, VND, 6);
        when(items.suppliersOf(inventoryItemId)).thenReturn(List.of(
                new SkuSupplierRow(rowOf(a), a), new SkuSupplierRow(rowOf(preferred), preferred),
                new SkuSupplierRow(rowOf(b), b)));
        when(purchasing.defaultSuppliers(anyCollection())).thenReturn(Map.of(inventoryItemId, preferredId));

        List<SkuSupplierSummary> result = service.suppliersOf("cup-01");

        assertThat(result).extracting(SkuSupplierSummary::supplierCode).containsExactly("P", "A", "B");
        assertThat(result.get(0).preferred()).isTrue();
        assertThat(result.get(1).preferred()).isFalse();
        assertThat(result.get(0).effectiveLeadTimeDays()).as("no item or supplier lead time: system default")
                .isEqualTo(com.stockflow.common.domain.CommercialTerms.LEAD_DAYS);
        assertThat(result.get(1).effectiveLeadTimeDays()).isEqualTo(4);
        assertThat(result.get(2).supplierStatus()).isEqualTo("INACTIVE");
    }

    private SupplierItem rowOf(CatalogSupplier owner) {
        return new SupplierItem(UUID.randomUUID(), owner.id(), inventoryItemId,
                SupplierItemTerms.stored("X", new Money(new BigDecimal("100"), VND), 10, 5, null), 0, NOW, NOW);
    }
}
