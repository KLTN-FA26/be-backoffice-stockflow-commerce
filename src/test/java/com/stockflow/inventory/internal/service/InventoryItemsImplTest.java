package com.stockflow.inventory.internal.service;

import com.stockflow.common.api.PageResponse;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.contracts.SkuInventoryControlChanged;
import com.stockflow.inventory.api.InventoryPolicy;
import com.stockflow.inventory.api.ItemLogistics;
import com.stockflow.inventory.api.RemovalStrategy;
import com.stockflow.inventory.api.SkuRetirement;
import com.stockflow.inventory.api.StorageClass;
import com.stockflow.inventory.api.TrackingMode;
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
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static com.stockflow.inventory.internal.service.InventoryFixtures.NOW;
import static com.stockflow.inventory.internal.service.InventoryFixtures.TODAY;
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
    private final ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
    @SuppressWarnings("unchecked")
    private final ObjectProvider<SkuRetirement> retirement = mock(ObjectProvider.class);
    private final SkuRetirement retired = mock(SkuRetirement.class);
    private final InventoryItemsImpl service = new InventoryItemsImpl(policies, search,
            Clock.fixed(NOW, ZoneOffset.UTC), users, events, retirement);

    private static ItemLogistics logistics(String unit, String barcode) {
        return new ItemLogistics(unit, barcode, BigDecimal.ONE, null, null, null, null, null, null, null, 1, 1,
                StorageClass.NORMAL, false, null, false);
    }

    private static InventoryPolicy policy(Integer reorder, Integer safety, Integer max, RemovalStrategy strategy,
                                          TrackingMode tracking, boolean expiry, Integer shelfLife) {
        return new InventoryPolicy(reorder, safety, max, strategy, tracking, expiry, shelfLife);
    }

    private static final InventoryPolicy CURRENT_POLICY =
            policy(20, 5, 100, RemovalStrategy.FEFO, TrackingMode.LOT, false, null);

    private ItemRow row(long version, String unit) {
        return new ItemRow(ID, SKU, version, logistics(unit, null), CURRENT_POLICY);
    }

    @BeforeEach
    void itemExists() {
        when(policies.findById(ID)).thenReturn(Optional.of(row(3, "PCS")));
        when(policies.findSku(ID)).thenReturn(Optional.of(SKU));
        when(users.current()).thenReturn(Optional.empty());
        when(retirement.getIfAvailable()).thenReturn(retired);
        when(policies.save(anyString(), anyLong(), any(), anyString())).thenReturn(true);
        when(policies.saveLogistics(anyString(), anyLong(), any(), anyString())).thenReturn(true);
    }

    @Nested
    @DisplayName("reading")
    class Reading {

        @Test
        @DisplayName("an unknown item is INVENTORY_ITEM_NOT_FOUND")
        void unknown() {
            UUID other = UUID.randomUUID();
            when(policies.findById(other)).thenReturn(Optional.empty());

            assertThat(codeOf(() -> service.get(other))).isEqualTo(ErrorCode.INVENTORY_ITEM_NOT_FOUND);
        }

        @Test
        @DisplayName("the detail evaluates the thresholds against today's usable on-hand: reorder needed at or under the point")
        void detailEvaluatesThresholds() {
            when(policies.usableOnHand(SKU, TODAY)).thenReturn(20L);

            var evaluation = service.get(ID).evaluation();

            assertThat(evaluation.usableOnHand()).isEqualTo(20L);
            assertThat(evaluation.reorderRequired()).isTrue();
            assertThat(evaluation.belowSafetyStock()).isFalse();
        }

        @Test
        @DisplayName("below the safety stock is flagged on its own")
        void belowSafety() {
            when(policies.usableOnHand(SKU, TODAY)).thenReturn(4L);

            var evaluation = service.get(ID).evaluation();

            assertThat(evaluation.reorderRequired()).isTrue();
            assertThat(evaluation.belowSafetyStock()).isTrue();
        }

        @Test
        @DisplayName("search turns blank filters into no filter, trims the others, and derives the tracking mode")
        void searchNormalisesFilters() {
            when(search.items(any(), any(Pageable.class))).thenReturn(new PageImpl<>(List.of(
                    new InventoryItemSearch.Row(ID, SKU, "PCS", "893", StorageClass.COLD, true, true, false))));

            PageResponse<InventoryItems.Summary> page = service.search(
                    new InventoryItems.ItemQuery("  ", " cup ", StorageClass.COLD, false, 0, 20));

            ArgumentCaptor<InventoryItemSearch.Criteria> criteria =
                    ArgumentCaptor.forClass(InventoryItemSearch.Criteria.class);
            verify(search).items(criteria.capture(), any(Pageable.class));
            assertThat(criteria.getValue()).isEqualTo(
                    new InventoryItemSearch.Criteria(null, "cup", StorageClass.COLD, false));
            assertThat(page.items().get(0).trackingMode()).isEqualTo(TrackingMode.LOT_SERIAL);
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

    @Nested
    @DisplayName("configuring the stock policy")
    class Configuring {

        @Test
        @DisplayName("the reorder point cannot be under the safety stock")
        void safetyAboveReorder() {
            assertThat(codeOf(() -> service.configure(ID, 3,
                    policy(5, 20, null, RemovalStrategy.FEFO, TrackingMode.NONE, false, null))))
                    .isEqualTo(ErrorCode.INVENTORY_POLICY_INVALID);
            verify(policies, never()).lock(anyString());
        }

        @Test
        @DisplayName("an order-up-to level under a threshold is refused, naming its own field")
        void maxBelowThreshold() {
            var thrown = org.assertj.core.api.Assertions.catchThrowableOfType(
                    com.stockflow.common.error.BusinessException.class,
                    () -> service.configure(ID, 3, policy(20, 5, 10, RemovalStrategy.FEFO, TrackingMode.NONE, false,
                            null)));

            assertThat(thrown.errorCode()).isEqualTo(ErrorCode.INVENTORY_POLICY_INVALID);
            assertThat(thrown.fieldErrors()).extracting(f -> f.field()).containsExactly("maxQty");
        }

        @Test
        @DisplayName("expiry tracking needs a lot-tracked item removed first-expired-first-out")
        void expiryNeedsLotAndFefo() {
            assertThat(codeOf(() -> service.configure(ID, 3,
                    policy(null, null, null, RemovalStrategy.FIFO, TrackingMode.LOT, true, null))))
                    .isEqualTo(ErrorCode.INVENTORY_POLICY_INVALID);
            assertThat(codeOf(() -> service.configure(ID, 3,
                    policy(null, null, null, RemovalStrategy.FEFO, TrackingMode.NONE, true, null))))
                    .isEqualTo(ErrorCode.INVENTORY_POLICY_INVALID);
        }

        @Test
        @DisplayName("a retired SKU is VARIANT_OBSOLETE")
        void retiredSku() {
            when(retired.retired(SKU)).thenReturn(true);

            assertThat(codeOf(() -> service.configure(ID, 3, CURRENT_POLICY))).isEqualTo(ErrorCode.VARIANT_OBSOLETE);
        }

        @Test
        @DisplayName("a stale version is OPTIMISTIC_LOCK and nothing is published")
        void staleVersion() {
            assertThat(codeOf(() -> service.configure(ID, 1, CURRENT_POLICY))).isEqualTo(ErrorCode.OPTIMISTIC_LOCK);
            verify(events, never()).publishEvent(any(Object.class));
        }

        @Test
        @DisplayName("changing the removal strategy while the SKU is being counted is COUNT_POLICY_CONFLICT")
        void operationalChangeDuringCount() {
            when(policies.hasActiveCount(SKU)).thenReturn(true);

            assertThat(codeOf(() -> service.configure(ID, 3,
                    policy(20, 5, 100, RemovalStrategy.FIFO, TrackingMode.LOT, false, null))))
                    .isEqualTo(ErrorCode.COUNT_POLICY_CONFLICT);
        }

        @Test
        @DisplayName("changing only thresholds during a count is allowed: they do not move stock")
        void thresholdChangeDuringCountIsAllowed() {
            when(policies.hasActiveCount(SKU)).thenReturn(true);

            service.configure(ID, 3, policy(30, 5, 100, RemovalStrategy.FEFO, TrackingMode.LOT, false, null));

            verify(policies).save(eq(SKU), eq(3L), any(), eq("system"));
        }

        @Test
        @DisplayName("stock that does not fit the new policy is INVENTORY_POLICY_STOCK_CONFLICT")
        void incompatibleWithStock() {
            when(policies.incompatible(eq(SKU), any())).thenReturn(true);

            assertThat(codeOf(() -> service.configure(ID, 3,
                    policy(20, 5, 100, RemovalStrategy.FEFO, TrackingMode.SERIAL, false, null))))
                    .isEqualTo(ErrorCode.INVENTORY_POLICY_STOCK_CONFLICT);
            verify(policies, never()).save(anyString(), anyLong(), any(), anyString());
        }

        @Test
        @DisplayName("a lost UPDATE race is OPTIMISTIC_LOCK")
        void lostUpdate() {
            when(policies.save(anyString(), anyLong(), any(), anyString())).thenReturn(false);

            assertThat(codeOf(() -> service.configure(ID, 3, CURRENT_POLICY))).isEqualTo(ErrorCode.OPTIMISTIC_LOCK);
            verify(events, never()).publishEvent(any(Object.class));
        }

        @Test
        @DisplayName("a saved policy publishes the control change with the NEW revision, so alerts are re-evaluated")
        void publishesNewRevision() {
            service.configure(ID, 3, policy(30, 5, 100, RemovalStrategy.FEFO, TrackingMode.LOT, false, null));

            ArgumentCaptor<Object> published = ArgumentCaptor.forClass(Object.class);
            verify(events).publishEvent(published.capture());
            assertThat(published.getValue()).isEqualTo(new SkuInventoryControlChanged(SKU, 4L, NOW));
        }
    }
}
