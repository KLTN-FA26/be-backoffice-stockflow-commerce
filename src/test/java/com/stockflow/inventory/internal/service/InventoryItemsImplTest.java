package com.stockflow.inventory.internal.service;

import com.stockflow.common.api.PageResponse;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.inventory.api.ItemLogistics;
import com.stockflow.inventory.api.SkuRetirement;
import com.stockflow.common.security.CurrentUserProvider;
import com.stockflow.inventory.internal.repository.InventoryItemSearch;
import com.stockflow.inventory.internal.repository.InventoryPolicyRepository;
import com.stockflow.inventory.internal.repository.InventoryPolicyRepository.ItemRow;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static com.stockflow.inventory.internal.service.InventoryFixtures.codeOf;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** SCRUM-357: who may edit an inventory item and under which guards (version, stock, counts, retirement). */
class InventoryItemsImplTest {

    private static final UUID ID = UUID.randomUUID();
    private static final String SKU = "CUP-12OZ";

    private final InventoryPolicyRepository policies = mock(InventoryPolicyRepository.class);
    private final InventoryItemSearch search = mock(InventoryItemSearch.class);
    private final CurrentUserProvider users = mock(CurrentUserProvider.class);
    @SuppressWarnings("unchecked")
    private final ObjectProvider<SkuRetirement> retirement = mock(ObjectProvider.class);
    private final SkuRetirement retired = mock(SkuRetirement.class);
    private final InventoryItemsImpl service = new InventoryItemsImpl(policies, search, users, retirement);

    private static ItemLogistics logistics(String unit, String barcode) {
        return new ItemLogistics(unit, barcode, BigDecimal.ONE, null, null, null, null, null, null, null, 1, 1);
    }

    private ItemRow row(long version, String unit) {
        return new ItemRow(ID, SKU, version, logistics(unit, null));
    }

    @BeforeEach
    void itemExists() {
        when(policies.findById(ID)).thenReturn(Optional.of(row(3, "PCS")));
        when(policies.findSku(ID)).thenReturn(Optional.of(SKU));
        when(users.current()).thenReturn(Optional.empty());
        when(retirement.getIfAvailable()).thenReturn(retired);
        when(policies.saveLogistics(anyString(), anyLong(), any(), anyString())).thenReturn(true);
    }

    @Nested
    @DisplayName("reading")
    class Reading {

        @Test
        @DisplayName("the detail has the logistics and no policy: the stock policy is fixed (kltn-docs 01 BR-17)")
        void detail() {
            var detail = service.get(ID);

            assertThat(detail.sku()).isEqualTo(SKU);
            assertThat(detail.logistics().unitOfMeasure()).isEqualTo("PCS");
        }

        @Test
        @DisplayName("an unknown item is INVENTORY_ITEM_NOT_FOUND")
        void unknown() {
            UUID other = UUID.randomUUID();
            when(policies.findById(other)).thenReturn(Optional.empty());

            assertThat(codeOf(() -> service.get(other))).isEqualTo(ErrorCode.INVENTORY_ITEM_NOT_FOUND);
        }

        @Test
        @DisplayName("search turns blank filters into no filter and trims the others")
        void searchNormalisesFilters() {
            when(search.items(any(), any(Pageable.class))).thenReturn(new PageImpl<>(List.of(
                    new InventoryItemSearch.Row(ID, SKU, "PCS", "893"))));

            PageResponse<InventoryItems.Summary> page = service.search(
                    new InventoryItems.ItemQuery("  ", " cup ", 0, 20));

            ArgumentCaptor<InventoryItemSearch.Criteria> criteria =
                    ArgumentCaptor.forClass(InventoryItemSearch.Criteria.class);
            verify(search).items(criteria.capture(), any(Pageable.class));
            assertThat(criteria.getValue()).isEqualTo(
                    new InventoryItemSearch.Criteria(null, "cup"));
            assertThat(page.items().get(0).sku()).isEqualTo(SKU);
        }
    }

    @Nested
    @DisplayName("describing the item (logistics)")
    class Describing {

        @Test
        @DisplayName("an invalid value is refused before the item is locked")
        void invalidIsRefusedFirst() {
            assertThat(codeOf(() -> service.describe(ID, 3, logistics("pcs", null))))
                    .isEqualTo(ErrorCode.VALIDATION_FAILED);
            verify(policies, never()).lock(anyString());
        }

        @Test
        @DisplayName("a retired SKU is VARIANT_OBSOLETE (not a 404), asked before the version")
        void retiredSku() {
            when(retired.retired(SKU)).thenReturn(true);

            assertThat(codeOf(() -> service.describe(ID, 99, logistics("PCS", null))))
                    .isEqualTo(ErrorCode.VARIANT_OBSOLETE);
            verify(policies, never()).saveLogistics(anyString(), anyLong(), any(), anyString());
        }

        @Test
        @DisplayName("when the module owning SKUs is absent nothing is retired")
        void noRetirementOwner() {
            when(retirement.getIfAvailable()).thenReturn(null);

            service.describe(ID, 3, logistics("PCS", null));

            verify(policies).saveLogistics(eq(SKU), eq(3L), any(), eq("system"));
        }

        @Test
        @DisplayName("a stale version is OPTIMISTIC_LOCK")
        void staleVersion() {
            assertThat(codeOf(() -> service.describe(ID, 2, logistics("PCS", null))))
                    .isEqualTo(ErrorCode.OPTIMISTIC_LOCK);
        }

        @Test
        @DisplayName("an item renamed between the SKU read and the lock is OPTIMISTIC_LOCK, not a 404")
        void renamedBetweenReadAndLock() {
            when(policies.findSku(ID)).thenReturn(Optional.of("OLD-NAME"));

            assertThat(codeOf(() -> service.describe(ID, 3, logistics("PCS", null))))
                    .isEqualTo(ErrorCode.OPTIMISTIC_LOCK);
            verify(policies).lock("OLD-NAME");
        }

        @Test
        @DisplayName("the unit of measure cannot change while stock is held")
        void unitLockedByStock() {
            when(policies.holdsStock(SKU)).thenReturn(true);

            assertThat(codeOf(() -> service.describe(ID, 3, logistics("BOX", null))))
                    .isEqualTo(ErrorCode.INVENTORY_POLICY_STOCK_CONFLICT);
        }

        @Test
        @DisplayName("the stock check is skipped when the unit is unchanged")
        void sameUnitIsNotStockChecked() {
            when(policies.holdsStock(SKU)).thenReturn(true);

            service.describe(ID, 3, logistics("PCS", "8930001"));

            verify(policies, never()).holdsStock(anyString());
            verify(policies).saveLogistics(eq(SKU), eq(3L), any(), eq("system"));
        }

        @Test
        @DisplayName("a new base unit cannot be a code one of the item's packing units already uses")
        void unitClashesWithPackingUnit() {
            when(policies.packingUnitUses(SKU, "BOX")).thenReturn(true);

            assertThat(codeOf(() -> service.describe(ID, 3, logistics("BOX", null))))
                    .isEqualTo(ErrorCode.UOM_CONVERSION_DUPLICATE);
        }

        @Test
        @DisplayName("a barcode another item owns is CONFLICT")
        void duplicateBarcode() {
            when(policies.saveLogistics(anyString(), anyLong(), any(), anyString()))
                    .thenThrow(new DuplicateKeyException("uk_inventory_items_barcode"));

            assertThat(codeOf(() -> service.describe(ID, 3, logistics("PCS", "8930001"))))
                    .isEqualTo(ErrorCode.CONFLICT);
        }

        @Test
        @DisplayName("a lost UPDATE race is OPTIMISTIC_LOCK")
        void lostUpdate() {
            when(policies.saveLogistics(anyString(), anyLong(), any(), anyString())).thenReturn(false);

            assertThat(codeOf(() -> service.describe(ID, 3, logistics("PCS", null))))
                    .isEqualTo(ErrorCode.OPTIMISTIC_LOCK);
        }
    }
}
