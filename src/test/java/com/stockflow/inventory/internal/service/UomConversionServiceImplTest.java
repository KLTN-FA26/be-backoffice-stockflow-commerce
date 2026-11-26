package com.stockflow.inventory.internal.service;

import com.stockflow.common.api.PageResponse;
import com.stockflow.common.domain.Sku;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.security.CurrentUserProvider;
import com.stockflow.inventory.api.UomConversion;
import com.stockflow.inventory.api.UomConversionDraft;
import com.stockflow.inventory.internal.repository.InventoryPolicyRepository;
import com.stockflow.inventory.internal.repository.UomConversionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** BR-10 (kltn-docs 01 §6): a unit that a document uses keeps its factor and code; 01 BR-03: one active carton. */
class UomConversionServiceImplTest {

    private static final Sku SKU = new Sku("CUP-12OZ");
    private static final UUID ITEM = UUID.randomUUID();
    /** The catalogue variant whose cartons these are (owner decision Q1, V20261231000100). */
    private static final UUID VARIANT = UUID.randomUUID();
    private static final UUID CARTON_ID = UUID.randomUUID();

    private final UomConversionRepository conversions = mock(UomConversionRepository.class);
    private final InventoryPolicyRepository items = mock(InventoryPolicyRepository.class);
    private final CurrentUserProvider users = mock(CurrentUserProvider.class);
    private final UomConversionServiceImpl service = new UomConversionServiceImpl(conversions, items, users,
            mock(BinStranding.class));

    @BeforeEach
    void itemExists() {
        when(conversions.itemId(SKU.code())).thenReturn(Optional.of(ITEM));
        when(conversions.variantId(SKU.code())).thenReturn(Optional.of(VARIANT));
        when(conversions.skusOfVariant(VARIANT)).thenReturn(List.of(SKU.code()));
        when(users.current()).thenReturn(Optional.empty());
    }

    private static UomConversionDraft draft(String unit, int factor, boolean active) {
        return new UomConversionDraft(unit, factor, null, null, null, null, active);
    }

    private static UomConversion unit(UUID id, String code, int factor, long version) {
        return new UomConversion(id, SKU.code(), code, factor, BigDecimal.ONE, null, null, null, true, version);
    }

    @Nested
    @DisplayName("reading units")
    class Reading {

        @Test
        @DisplayName("a SKU with no inventory item is INVENTORY_ITEM_NOT_FOUND, not an empty list")
        void unknownItem() {
            when(conversions.itemId("NOPE")).thenReturn(Optional.empty());

            assertThat(codeOf(() -> service.conversions(new Sku("NOPE"), 0, 10)))
                    .isEqualTo(ErrorCode.INVENTORY_ITEM_NOT_FOUND);
        }

        @Test
        @DisplayName("the page carries the repository's total, so a client can compute the page count")
        void pageWithTotal() {
            UomConversion carton = unit(CARTON_ID, "CARTON", 1000, 0);
            when(conversions.page(SKU.code(), 10, 0)).thenReturn(List.of(carton));
            when(conversions.count(SKU.code())).thenReturn(1L);

            PageResponse<UomConversion> page = service.conversions(SKU, 0, 10);

            assertThat(page.items()).containsExactly(carton);
            assertThat(page.totalElements()).isEqualTo(1L);
        }

        @Test
        @DisplayName("one unit: a unit of another SKU is UOM_CONVERSION_NOT_FOUND")
        void unitOfAnotherSku() {
            when(conversions.find(SKU.code(), CARTON_ID)).thenReturn(Optional.empty());

            assertThat(codeOf(() -> service.conversion(SKU, CARTON_ID))).isEqualTo(ErrorCode.UOM_CONVERSION_NOT_FOUND);
        }

        @Test
        @DisplayName("units by id come back keyed by id")
        void byIds() {
            UomConversion carton = unit(CARTON_ID, "CARTON", 1000, 0);
            when(conversions.find(SKU.code(), List.of(CARTON_ID))).thenReturn(List.of(carton));

            assertThat(service.conversions(SKU, List.of(CARTON_ID))).isEqualTo(Map.of(CARTON_ID, carton));
        }

        @Test
        @DisplayName("conversionsOf asks once for each distinct SKU")
        void conversionsOfDeduplicates() {
            when(conversions.bySkus(List.of("CUP-12OZ", "LID-12OZ"))).thenReturn(Map.of());

            service.conversionsOf(List.of(SKU, new Sku("LID-12OZ"), SKU));

            verify(conversions).bySkus(List.of("CUP-12OZ", "LID-12OZ"));
        }
    }

    @Nested
    @DisplayName("declaring a unit")
    class Adding {

        @Test
        @DisplayName("BR-09: the factor is a positive whole number of pieces")
        void factorMustBePositive() {
            assertThat(codeOf(() -> service.add(SKU, draft("CARTON", 0, true))))
                    .isEqualTo(ErrorCode.VALIDATION_FAILED);
            verify(conversions, never()).insert(any(), any(), any(), anyString());
        }

        @Test
        @DisplayName("a unit code is 1-16 upper-case letters, digits or underscore")
        void unitCodeShape() {
            assertThat(codeOf(() -> service.add(SKU, draft("carton", 12, true))))
                    .isEqualTo(ErrorCode.VALIDATION_FAILED);
        }

        @Test
        @DisplayName("a code the item already uses is UOM_CONVERSION_DUPLICATE")
        void duplicateUnit() {
            when(conversions.unitTaken(VARIANT, "CARTON", null)).thenReturn(true);

            assertThat(codeOf(() -> service.add(SKU, draft("CARTON", 12, true))))
                    .isEqualTo(ErrorCode.UOM_CONVERSION_DUPLICATE);
            verify(conversions, never()).insert(any(), any(), any(), anyString());
        }

        @Test
        @DisplayName("01 BR-03: a second active carton is UOM_CARTON_ALREADY_DECLARED; a retired one is declared freely")
        void secondActiveCartonIsRefused() {
            when(conversions.activeCartonOf(VARIANT, null)).thenReturn(Optional.of("CARTON"));

            assertThat(codeOf(() -> service.add(SKU, draft("BOX", 12, true))))
                    .isEqualTo(ErrorCode.UOM_CARTON_ALREADY_DECLARED);
            verify(conversions, never()).insert(any(), any(), any(), anyString());

            when(conversions.find(eq(SKU.code()), any(UUID.class))).thenAnswer(call ->
                    Optional.of(unit(call.getArgument(1), "BOX", 12, 0)));
            service.add(SKU, draft("BOX", 12, false));
            verify(conversions).insert(any(), eq(VARIANT), any(), eq("system"));
        }

        @Test
        @DisplayName("takes the SKU lock first, then inserts the unit and reads it back")
        void lockThenInsert() {
            UUID[] inserted = new UUID[1];
            org.mockito.Mockito.doAnswer(call -> {
                inserted[0] = call.getArgument(0);
                return null;
            }).when(conversions).insert(any(), eq(VARIANT), any(), eq("system"));
            when(conversions.find(eq(SKU.code()), any(UUID.class))).thenAnswer(call ->
                    Optional.of(unit(call.getArgument(1), "CARTON", 12, 0)));

            UomConversion created = service.add(SKU, draft("CARTON", 12, true));

            InOrder order = inOrder(items, conversions);
            order.verify(items).lock(SKU.code());
            order.verify(conversions).insert(any(), eq(VARIANT), any(), eq("system"));
            assertThat(created.id()).isEqualTo(inserted[0]);
        }

        @Test
        @DisplayName("an unknown SKU is refused with INVENTORY_ITEM_NOT_FOUND, before any lock")
        void unknownSku() {
            when(conversions.variantId("NOPE")).thenReturn(Optional.empty());

            assertThat(codeOf(() -> service.add(new Sku("NOPE"), draft("CARTON", 12, true))))
                    .isEqualTo(ErrorCode.INVENTORY_ITEM_NOT_FOUND);
            verify(items, never()).lock(any());
        }

        @Test
        @DisplayName("Q1: the carton is the variant's, so the write locks every SKU packed in it, in sorted order")
        void locksEverySkuOfTheVariant() {
            when(conversions.skusOfVariant(VARIANT)).thenReturn(List.of(SKU.code(), "CUP-12OZ-LA", "CUP-12OZ-MOC"));
            when(conversions.find(eq(SKU.code()), any(UUID.class))).thenAnswer(call ->
                    Optional.of(unit(call.getArgument(1), "CARTON", 12, 0)));

            service.add(SKU, draft("CARTON", 12, true));

            InOrder order = inOrder(items, conversions);
            order.verify(items).lock(SKU.code());
            order.verify(items).lock("CUP-12OZ-LA");
            order.verify(items).lock("CUP-12OZ-MOC");
            order.verify(conversions).insert(any(), eq(VARIANT), any(), eq("system"));
        }
    }

    @Nested
    @DisplayName("changing a unit")
    class Changing {

        private final UomConversion carton = unit(CARTON_ID, "CARTON", 1000, 4);

        @BeforeEach
        void cartonExists() {
            when(conversions.find(SKU.code(), CARTON_ID)).thenReturn(Optional.of(carton));
            when(conversions.update(eq(CARTON_ID), eq(4L), any(), anyString())).thenReturn(true);
        }

        @Test
        @DisplayName("BR-10: a unit a document uses cannot change its factor")
        void usedUnitKeepsItsFactor() {
            when(conversions.inUse(CARTON_ID)).thenReturn(true);

            assertThat(codeOf(() -> service.change(SKU, CARTON_ID, 4, draft("CARTON", 500, true))))
                    .isEqualTo(ErrorCode.UOM_CONVERSION_IN_USE);
            verify(conversions, never()).update(any(), anyLong(), any(), anyString());
        }

        @Test
        @DisplayName("BR-10: nor its code")
        void usedUnitKeepsItsCode() {
            when(conversions.inUse(CARTON_ID)).thenReturn(true);

            assertThat(codeOf(() -> service.change(SKU, CARTON_ID, 4, draft("BOX", 1000, true))))
                    .isEqualTo(ErrorCode.UOM_CONVERSION_IN_USE);
        }

        @Test
        @DisplayName("BR-10 only bites when the factor or code changes: a used unit's weight stays editable")
        void usedUnitStaysEditableInOtherFields() {
            when(conversions.inUse(CARTON_ID)).thenReturn(true);
            when(conversions.find(SKU.code(), CARTON_ID)).thenReturn(Optional.of(carton),
                    Optional.of(unit(CARTON_ID, "CARTON", 1000, 5)));

            UomConversion changed = service.change(SKU, CARTON_ID, 4, new UomConversionDraft("CARTON", 1000,
                    new BigDecimal("2.5"), null, null, null, true));

            assertThat(changed.version()).isEqualTo(5);
            verify(conversions, never()).inUse(CARTON_ID);
            verify(conversions).update(eq(CARTON_ID), eq(4L), any(), eq("system"));
        }

        @Test
        @DisplayName("an unused unit may be re-declared with another factor")
        void unusedUnitMayChangeFactor() {
            when(conversions.inUse(CARTON_ID)).thenReturn(false);

            service.change(SKU, CARTON_ID, 4, draft("CARTON", 500, true));

            verify(conversions).update(eq(CARTON_ID), eq(4L), any(), eq("system"));
        }

        @Test
        @DisplayName("01 BR-03: re-activating a retired unit while another is active is UOM_CARTON_ALREADY_DECLARED")
        void reactivatingBesideAnActiveCartonIsRefused() {
            UUID retiredId = UUID.randomUUID();
            UomConversion retired = new UomConversion(retiredId, SKU.code(), "OLD", 1000, BigDecimal.ONE, null,
                    null, null, false, 2);
            when(conversions.find(SKU.code(), retiredId)).thenReturn(Optional.of(retired));
            when(conversions.activeCartonOf(VARIANT, retiredId)).thenReturn(Optional.of("CARTON"));

            assertThat(codeOf(() -> service.change(SKU, retiredId, 2, draft("OLD", 1000, true))))
                    .isEqualTo(ErrorCode.UOM_CARTON_ALREADY_DECLARED);
            verify(conversions, never()).update(any(), anyLong(), any(), anyString());
        }

        @Test
        @DisplayName("a stale version is OPTIMISTIC_LOCK, before anything is written")
        void staleVersion() {
            assertThat(codeOf(() -> service.change(SKU, CARTON_ID, 3, draft("CARTON", 1000, true))))
                    .isEqualTo(ErrorCode.OPTIMISTIC_LOCK);
            verify(conversions, never()).update(any(), anyLong(), any(), anyString());
        }

        @Test
        @DisplayName("a concurrent writer that wins the UPDATE turns the change into OPTIMISTIC_LOCK")
        void lostUpdate() {
            when(conversions.update(eq(CARTON_ID), eq(4L), any(), anyString())).thenReturn(false);

            assertThat(codeOf(() -> service.change(SKU, CARTON_ID, 4, draft("CARTON", 1000, true))))
                    .isEqualTo(ErrorCode.OPTIMISTIC_LOCK);
        }

        @Test
        @DisplayName("renaming to a code another unit of the item has is UOM_CONVERSION_DUPLICATE")
        void renameToTakenCode() {
            when(conversions.unitTaken(VARIANT, "BOX", CARTON_ID)).thenReturn(true);

            assertThat(codeOf(() -> service.change(SKU, CARTON_ID, 4, draft("BOX", 1000, true))))
                    .isEqualTo(ErrorCode.UOM_CONVERSION_DUPLICATE);
        }

        @Test
        @DisplayName("a unit of another SKU is UOM_CONVERSION_NOT_FOUND")
        void unknownUnit() {
            UUID other = UUID.randomUUID();
            when(conversions.find(SKU.code(), other)).thenReturn(Optional.empty());

            assertThat(codeOf(() -> service.change(SKU, other, 1, draft("CARTON", 12, true))))
                    .isEqualTo(ErrorCode.UOM_CONVERSION_NOT_FOUND);
        }
    }

    @Nested
    @DisplayName("retiring a unit")
    class Removing {

        @Test
        @DisplayName("BR-10: a unit a document uses cannot be deleted")
        void usedUnitCannotBeDeleted() {
            when(conversions.find(SKU.code(), CARTON_ID)).thenReturn(Optional.of(unit(CARTON_ID, "CARTON", 12, 1)));
            when(conversions.inUse(CARTON_ID)).thenReturn(true);

            assertThat(codeOf(() -> service.remove(SKU, CARTON_ID, 1))).isEqualTo(ErrorCode.UOM_CONVERSION_IN_USE);
            verify(conversions, never()).delete(any(), anyLong());
        }

        @Test
        @DisplayName("a stale version is OPTIMISTIC_LOCK")
        void staleVersion() {
            when(conversions.find(SKU.code(), CARTON_ID)).thenReturn(Optional.of(unit(CARTON_ID, "CARTON", 12, 1)));

            assertThat(codeOf(() -> service.remove(SKU, CARTON_ID, 0))).isEqualTo(ErrorCode.OPTIMISTIC_LOCK);
        }

        @Test
        @DisplayName("a lost DELETE race is OPTIMISTIC_LOCK")
        void lostDelete() {
            when(conversions.find(SKU.code(), CARTON_ID)).thenReturn(Optional.of(unit(CARTON_ID, "CARTON", 12, 1)));
            when(conversions.delete(CARTON_ID, 1)).thenReturn(false);

            assertThat(codeOf(() -> service.remove(SKU, CARTON_ID, 1))).isEqualTo(ErrorCode.OPTIMISTIC_LOCK);
        }

        @Test
        @DisplayName("an unused unit is deleted under the SKU lock")
        void deleted() {
            when(conversions.find(SKU.code(), CARTON_ID)).thenReturn(Optional.of(unit(CARTON_ID, "CARTON", 12, 1)));
            when(conversions.delete(CARTON_ID, 1)).thenReturn(true);

            service.remove(SKU, CARTON_ID, 1);

            InOrder order = inOrder(items, conversions);
            order.verify(items).lock(SKU.code());
            order.verify(conversions).delete(CARTON_ID, 1);
        }
    }
}
