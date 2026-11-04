package com.stockflow.inventory.internal.service;

import com.stockflow.common.domain.Sku;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.security.CurrentUserProvider;
import com.stockflow.inventory.api.InventoryItemPurchasing;
import com.stockflow.inventory.api.ItemPurchasing;
import com.stockflow.inventory.internal.repository.InventoryPolicyRepository;
import com.stockflow.inventory.internal.repository.ItemPurchasingRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static com.stockflow.inventory.internal.service.InventoryFixtures.codeOf;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** kltn-docs 01: an item's purchasing data (default supplier, reference price, lead time, MOQ) and its guards. */
class ItemPurchasingServiceImplTest {

    private static final Sku SKU = new Sku("CUP-12OZ");
    private static final UUID ITEM = UUID.randomUUID();
    private static final UUID SUPPLIER = UUID.randomUUID();

    private final ItemPurchasingRepository purchasing = mock(ItemPurchasingRepository.class);
    private final InventoryPolicyRepository items = mock(InventoryPolicyRepository.class);
    private final CurrentUserProvider users = mock(CurrentUserProvider.class);
    private final ItemPurchasingServiceImpl service = new ItemPurchasingServiceImpl(purchasing, items, users);

    private static ItemPurchasing data(BigDecimal price, String currency, Integer leadTime, Integer moq) {
        return new ItemPurchasing(SUPPLIER, price, currency, leadTime, moq);
    }

    private static InventoryItemPurchasing stored(long version) {
        return new InventoryItemPurchasing(SKU.code(), version, data(new BigDecimal("1200.50"), "VND", 7, 100));
    }

    @BeforeEach
    void actorIsTheSystem() {
        when(users.current()).thenReturn(Optional.empty());
    }

    @Test
    @DisplayName("an unknown SKU is INVENTORY_ITEM_NOT_FOUND")
    void unknownItem() {
        when(purchasing.find(SKU.code())).thenReturn(Optional.empty());

        assertThat(codeOf(() -> service.purchasing(SKU))).isEqualTo(ErrorCode.INVENTORY_ITEM_NOT_FOUND);
    }

    @Test
    @DisplayName("an invalid value is refused before the item is locked")
    void validatedBeforeLock() {
        assertThat(codeOf(() -> service.describe(SKU, 1, data(new BigDecimal("0"), "VND", null, null))))
                .isEqualTo(ErrorCode.VALIDATION_FAILED);
        verify(items, never()).lock(anyString());
    }

    @Test
    @DisplayName("a stale version is OPTIMISTIC_LOCK and nothing is saved")
    void staleVersion() {
        when(purchasing.find(SKU.code())).thenReturn(Optional.of(stored(4)));

        assertThat(codeOf(() -> service.describe(SKU, 3, data(null, null, 7, 100)))).isEqualTo(ErrorCode.OPTIMISTIC_LOCK);
        verify(purchasing, never()).save(anyString(), anyLong(), any(), anyString());
    }

    @Test
    @DisplayName("a lost UPDATE race is OPTIMISTIC_LOCK")
    void lostUpdate() {
        when(purchasing.find(SKU.code())).thenReturn(Optional.of(stored(4)));
        when(purchasing.save(anyString(), anyLong(), any(), anyString())).thenReturn(false);

        assertThat(codeOf(() -> service.describe(SKU, 4, data(null, null, 7, 100)))).isEqualTo(ErrorCode.OPTIMISTIC_LOCK);
    }

    @Test
    @DisplayName("a current version is saved under the SKU lock, as the system when nobody is signed in, and read back")
    void saved() {
        when(purchasing.find(SKU.code())).thenReturn(Optional.of(stored(4)), Optional.of(stored(5)));
        when(purchasing.save(anyString(), anyLong(), any(), anyString())).thenReturn(true);

        InventoryItemPurchasing result = service.describe(SKU, 4, data(new BigDecimal("1200.50"), "VND", 7, 100));

        assertThat(result.version()).isEqualTo(5);
        InOrder order = inOrder(items, purchasing);
        order.verify(items).lock("CUP-12OZ");
        order.verify(purchasing).save(org.mockito.ArgumentMatchers.eq("CUP-12OZ"), org.mockito.ArgumentMatchers.eq(4L),
                any(), org.mockito.ArgumentMatchers.eq("system"));
    }

    @Test
    @DisplayName("default suppliers are looked up by item id")
    void defaultSuppliers() {
        when(purchasing.defaultSuppliers(List.of(ITEM))).thenReturn(Map.of(ITEM, SUPPLIER));

        assertThat(service.defaultSuppliers(List.of(ITEM))).isEqualTo(Map.of(ITEM, SUPPLIER));
    }

    @Test
    @DisplayName("assigning a default supplier takes the item's SKU lock first, then writes")
    void assignLocksFirst() {
        when(purchasing.skuOf(ITEM)).thenReturn(Optional.of("CUP-12OZ"));

        service.assignDefaultSupplier(ITEM, SUPPLIER);

        InOrder order = inOrder(items, purchasing);
        order.verify(items).lock("CUP-12OZ");
        order.verify(purchasing).assignDefaultSupplier(ITEM, SUPPLIER, "system");
    }

    @Test
    @DisplayName("clearing a default supplier takes the lock first as well")
    void clearLocksFirst() {
        when(purchasing.skuOf(ITEM)).thenReturn(Optional.of("CUP-12OZ"));

        service.clearDefaultSupplier(ITEM, SUPPLIER);

        InOrder order = inOrder(items, purchasing);
        order.verify(items).lock("CUP-12OZ");
        order.verify(purchasing).clearDefaultSupplier(ITEM, SUPPLIER, "system");
    }

    @Test
    @DisplayName("assigning on an unknown item is INVENTORY_ITEM_NOT_FOUND, with no lock taken")
    void assignUnknownItem() {
        when(purchasing.skuOf(ITEM)).thenReturn(Optional.empty());

        assertThat(codeOf(() -> service.assignDefaultSupplier(ITEM, SUPPLIER))).isEqualTo(ErrorCode.INVENTORY_ITEM_NOT_FOUND);
        assertThat(codeOf(() -> service.clearDefaultSupplier(ITEM, SUPPLIER))).isEqualTo(ErrorCode.INVENTORY_ITEM_NOT_FOUND);
        verify(items, never()).lock(anyString());
    }
}
