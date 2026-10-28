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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** BR-10 (kltn-docs 01 §6): a unit that a document uses keeps its factor and code; defaults move atomically. */
class UomConversionServiceImplTest {

    private static final Sku SKU = new Sku("CUP-12OZ");
    private static final UUID ITEM = UUID.randomUUID();
    private static final UUID CARTON_ID = UUID.randomUUID();

    private final UomConversionRepository conversions = mock(UomConversionRepository.class);
    private final InventoryPolicyRepository items = mock(InventoryPolicyRepository.class);
    private final CurrentUserProvider users = mock(CurrentUserProvider.class);
    private final UomConversionServiceImpl service = new UomConversionServiceImpl(conversions, items, users);

    @BeforeEach
    void itemExists() {
        when(conversions.itemId(SKU.code())).thenReturn(Optional.of(ITEM));
        when(users.current()).thenReturn(Optional.empty());
    }

    private static UomConversionDraft draft(String unit, int factor, boolean defaultPurchase, boolean active,
                                            boolean defaultShipping) {
        return new UomConversionDraft(unit, factor, null, null, null, null, defaultPurchase, active,
                defaultShipping);
    }

    private static UomConversion unit(UUID id, String code, int factor, boolean base, boolean defaultPurchase,
                                      boolean defaultShipping, long version) {
        return new UomConversion(id, SKU.code(), code, factor, base, BigDecimal.ONE, null, null, null,
                defaultPurchase, true, version, defaultShipping);
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
            assertThat(codeOf(() -> service.baseUnit(new Sku("NOPE")))).isEqualTo(ErrorCode.INVENTORY_ITEM_NOT_FOUND);
        }

        @Test
        @DisplayName("the page carries the repository's total, so a client can compute the page count")
        void pageWithTotal() {
            UomConversion carton = unit(CARTON_ID, "CARTON", 1000, false, false, false, 0);
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
            UomConversion carton = unit(CARTON_ID, "CARTON", 1000, false, false, false, 0);
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

        @Test
        @DisplayName("every item has a base unit; a missing one is a data fault, not a client error")
        void baseUnitMissing() {
            when(conversions.base(SKU.code())).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.baseUnit(SKU)).isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining(SKU.code());
        }

        @Test
        @DisplayName("the base unit is returned as stored")
        void baseUnit() {
            UomConversion base = unit(UUID.randomUUID(), "PCS", 1, true, true, false, 3);
            when(conversions.base(SKU.code())).thenReturn(Optional.of(base));

            assertThat(service.baseUnit(SKU)).isSameAs(base);
        }
    }

    @Nested
    @DisplayName("declaring a unit")
    class Adding {

        @Test
        @DisplayName("BR-09: the factor is a positive whole number of base units")
        void factorMustBePositive() {
            assertThat(codeOf(() -> service.add(SKU, draft("CARTON", 0, false, true, false))))
                    .isEqualTo(ErrorCode.VALIDATION_FAILED);
            verify(conversions, never()).insert(any(), any(), any(), anyString());
        }

        @Test
        @DisplayName("a unit code is 1-16 upper-case letters, digits or underscore")
        void unitCodeShape() {
            assertThat(codeOf(() -> service.add(SKU, draft("carton", 12, false, true, false))))
                    .isEqualTo(ErrorCode.VALIDATION_FAILED);
        }

        @Test
        @DisplayName("a retired unit cannot be the default purchase or default shipping unit")
        void retiredCannotBeDefault() {
            assertThat(codeOf(() -> service.add(SKU, draft("CARTON", 12, true, false, false))))
                    .isEqualTo(ErrorCode.VALIDATION_FAILED);
            assertThat(codeOf(() -> service.add(SKU, draft("CARTON", 12, false, false, true))))
                    .isEqualTo(ErrorCode.VALIDATION_FAILED);
        }

        @Test
        @DisplayName("a code the item already uses is UOM_CONVERSION_DUPLICATE")
        void duplicateUnit() {
            when(conversions.unitTaken(ITEM, "CARTON", null)).thenReturn(true);

            assertThat(codeOf(() -> service.add(SKU, draft("CARTON", 12, false, true, false))))
                    .isEqualTo(ErrorCode.UOM_CONVERSION_DUPLICATE);
            verify(conversions, never()).insert(any(), any(), any(), anyString());
        }

        @Test
        @DisplayName("takes the SKU lock first, then inserts the unit and reads it back")
        void lockThenInsert() {
            UUID[] inserted = new UUID[1];
            org.mockito.Mockito.doAnswer(call -> {
                inserted[0] = call.getArgument(0);
                return null;
            }).when(conversions).insert(any(), eq(ITEM), any(), eq("system"));
            when(conversions.find(eq(SKU.code()), any(UUID.class))).thenAnswer(call ->
                    Optional.of(unit(call.getArgument(1), "CARTON", 12, false, false, false, 0)));

            UomConversion created = service.add(SKU, draft("CARTON", 12, false, true, false));

            InOrder order = inOrder(items, conversions);
            order.verify(items).lock(SKU.code());
            order.verify(conversions).insert(any(), eq(ITEM), any(), eq("system"));
            assertThat(created.id()).isEqualTo(inserted[0]);
            verify(conversions, never()).clearDefaultPurchase(any(), any(), anyString());
            verify(conversions, never()).clearDefaultShipping(any(), any(), anyString());
        }

        @Test
        @DisplayName("marking the new unit default purchase and default shipping unmarks the previous ones first")
        void newDefaultsReplaceOldOnes() {
            when(conversions.find(eq(SKU.code()), any(UUID.class))).thenAnswer(call ->
                    Optional.of(unit(call.getArgument(1), "CARTON", 12, false, true, true, 0)));

            service.add(SKU, draft("CARTON", 12, true, true, true));

            InOrder order = inOrder(conversions);
            order.verify(conversions).clearDefaultPurchase(eq(ITEM), any(UUID.class), eq("system"));
            order.verify(conversions).clearDefaultShipping(eq(ITEM), any(UUID.class), eq("system"));
            order.verify(conversions).insert(any(UUID.class), eq(ITEM), any(), eq("system"));
        }

        @Test
        @DisplayName("an unknown SKU is refused after the lock, with INVENTORY_ITEM_NOT_FOUND")
        void unknownSku() {
            when(conversions.itemId("NOPE")).thenReturn(Optional.empty());

            assertThat(codeOf(() -> service.add(new Sku("NOPE"), draft("CARTON", 12, false, true, false))))
                    .isEqualTo(ErrorCode.INVENTORY_ITEM_NOT_FOUND);
        }
    }

    @Nested
    @DisplayName("changing a unit")
    class Changing {

        private final UomConversion carton = unit(CARTON_ID, "CARTON", 1000, false, false, false, 4);

        @BeforeEach
        void cartonExists() {
            when(conversions.find(SKU.code(), CARTON_ID)).thenReturn(Optional.of(carton));
            when(conversions.update(eq(CARTON_ID), eq(4L), any(), anyBoolean(), anyString())).thenReturn(true);
        }

        @Test
        @DisplayName("BR-10: a unit a document uses cannot change its factor")
        void usedUnitKeepsItsFactor() {
            when(conversions.inUse(CARTON_ID)).thenReturn(true);

            assertThat(codeOf(() -> service.change(SKU, CARTON_ID, 4, draft("CARTON", 500, false, true, false))))
                    .isEqualTo(ErrorCode.UOM_CONVERSION_IN_USE);
            verify(conversions, never()).update(any(), anyLong(), any(), anyBoolean(), anyString());
        }

        @Test
        @DisplayName("BR-10: nor its code")
        void usedUnitKeepsItsCode() {
            when(conversions.inUse(CARTON_ID)).thenReturn(true);

            assertThat(codeOf(() -> service.change(SKU, CARTON_ID, 4, draft("BOX", 1000, false, true, false))))
                    .isEqualTo(ErrorCode.UOM_CONVERSION_IN_USE);
        }

        @Test
        @DisplayName("BR-10 only bites when the factor or code changes: a used unit's weight and defaults stay editable")
        void usedUnitStaysEditableInOtherFields() {
            when(conversions.inUse(CARTON_ID)).thenReturn(true);
            when(conversions.find(SKU.code(), CARTON_ID)).thenReturn(Optional.of(carton),
                    Optional.of(unit(CARTON_ID, "CARTON", 1000, false, true, false, 5)));

            UomConversion changed = service.change(SKU, CARTON_ID, 4, draft("CARTON", 1000, true, true, false));

            assertThat(changed.defaultPurchase()).isTrue();
            verify(conversions, never()).inUse(CARTON_ID);
            verify(conversions).clearDefaultPurchase(ITEM, CARTON_ID, "system");
        }

        @Test
        @DisplayName("an unused unit may be re-declared with another factor")
        void unusedUnitMayChangeFactor() {
            when(conversions.inUse(CARTON_ID)).thenReturn(false);

            service.change(SKU, CARTON_ID, 4, draft("CARTON", 500, false, true, false));

            verify(conversions).update(eq(CARTON_ID), eq(4L), any(), eq(false), eq("system"));
            verify(conversions).defaultToBaseIfNone(ITEM, "system");
        }

        @Test
        @DisplayName("a stale version is OPTIMISTIC_LOCK, before anything is written")
        void staleVersion() {
            assertThat(codeOf(() -> service.change(SKU, CARTON_ID, 3, draft("CARTON", 1000, false, true, false))))
                    .isEqualTo(ErrorCode.OPTIMISTIC_LOCK);
            verify(conversions, never()).update(any(), anyLong(), any(), anyBoolean(), anyString());
        }

        @Test
        @DisplayName("a concurrent writer that wins the UPDATE turns the change into OPTIMISTIC_LOCK")
        void lostUpdate() {
            when(conversions.update(eq(CARTON_ID), eq(4L), any(), anyBoolean(), anyString())).thenReturn(false);

            assertThat(codeOf(() -> service.change(SKU, CARTON_ID, 4, draft("CARTON", 1000, false, true, false))))
                    .isEqualTo(ErrorCode.OPTIMISTIC_LOCK);
        }

        @Test
        @DisplayName("renaming to a code another unit of the item has is UOM_CONVERSION_DUPLICATE")
        void renameToTakenCode() {
            when(conversions.unitTaken(ITEM, "BOX", CARTON_ID)).thenReturn(true);

            assertThat(codeOf(() -> service.change(SKU, CARTON_ID, 4, draft("BOX", 1000, false, true, false))))
                    .isEqualTo(ErrorCode.UOM_CONVERSION_DUPLICATE);
        }

        @Test
        @DisplayName("a unit of another SKU is UOM_CONVERSION_NOT_FOUND")
        void unknownUnit() {
            UUID other = UUID.randomUUID();
            when(conversions.find(SKU.code(), other)).thenReturn(Optional.empty());

            assertThat(codeOf(() -> service.change(SKU, other, 1, draft("CARTON", 12, false, true, false))))
                    .isEqualTo(ErrorCode.UOM_CONVERSION_NOT_FOUND);
        }

        @Test
        @DisplayName("an already-default unit does not clear the others again")
        void alreadyDefaultIsNotCleared() {
            UomConversion shipping = unit(CARTON_ID, "CARTON", 1000, false, false, true, 4);
            when(conversions.find(SKU.code(), CARTON_ID)).thenReturn(Optional.of(shipping));

            service.change(SKU, CARTON_ID, 4, draft("CARTON", 1000, false, true, true));

            verify(conversions, never()).clearDefaultShipping(any(), any(), anyString());
        }

        @Test
        @DisplayName("marking a unit default shipping unmarks the previous one in the same transaction, not a 409")
        void newDefaultShippingReplacesOld() {
            service.change(SKU, CARTON_ID, 4, draft("CARTON", 1000, false, true, true));

            verify(conversions).clearDefaultShipping(ITEM, CARTON_ID, "system");
        }
    }

    @Nested
    @DisplayName("changing the base unit")
    class ChangingBase {

        private final UUID baseId = UUID.randomUUID();
        private final UomConversion base = unit(baseId, "PCS", 1, true, true, false, 2);

        @BeforeEach
        void baseExists() {
            when(conversions.find(SKU.code(), baseId)).thenReturn(Optional.of(base));
            when(conversions.update(eq(baseId), eq(2L), any(), eq(true), anyString())).thenReturn(true);
        }

        @Test
        @DisplayName("the base unit cannot be retired")
        void cannotRetire() {
            assertThat(codeOf(() -> service.change(SKU, baseId, 2, draft("PCS", 1, true, false, false))))
                    .isEqualTo(ErrorCode.UOM_CONVERSION_IN_USE);
        }

        @Test
        @DisplayName("it stays the default purchase unit until another unit is marked")
        void staysDefaultPurchase() {
            assertThat(codeOf(() -> service.change(SKU, baseId, 2, draft("PCS", 1, false, true, false))))
                    .isEqualTo(ErrorCode.VALIDATION_FAILED);
        }

        @Test
        @DisplayName("its code and factor are not consulted: no in-use probe is made for the base unit")
        void noInUseProbe() {
            service.change(SKU, baseId, 2, draft("PCS", 1, true, true, true));

            verify(conversions, never()).inUse(baseId);
            verify(conversions, never()).unitTaken(any(), anyString(), any());
            verify(conversions).update(eq(baseId), eq(2L), any(), eq(true), eq("system"));
        }
    }

    @Nested
    @DisplayName("retiring a unit")
    class Removing {

        @Test
        @DisplayName("the base unit cannot be deleted")
        void baseCannotBeDeleted() {
            UUID baseId = UUID.randomUUID();
            when(conversions.find(SKU.code(), baseId)).thenReturn(Optional.of(unit(baseId, "PCS", 1, true, true,
                    false, 0)));

            assertThat(codeOf(() -> service.remove(SKU, baseId, 0))).isEqualTo(ErrorCode.UOM_CONVERSION_IN_USE);
            verify(conversions, never()).delete(any(), anyLong());
        }

        @Test
        @DisplayName("BR-10: a unit a document uses cannot be deleted")
        void usedUnitCannotBeDeleted() {
            when(conversions.find(SKU.code(), CARTON_ID)).thenReturn(Optional.of(unit(CARTON_ID, "CARTON", 12, false,
                    false, false, 1)));
            when(conversions.inUse(CARTON_ID)).thenReturn(true);

            assertThat(codeOf(() -> service.remove(SKU, CARTON_ID, 1))).isEqualTo(ErrorCode.UOM_CONVERSION_IN_USE);
            verify(conversions, never()).delete(any(), anyLong());
        }

        @Test
        @DisplayName("a stale version is OPTIMISTIC_LOCK")
        void staleVersion() {
            when(conversions.find(SKU.code(), CARTON_ID)).thenReturn(Optional.of(unit(CARTON_ID, "CARTON", 12, false,
                    false, false, 1)));

            assertThat(codeOf(() -> service.remove(SKU, CARTON_ID, 0))).isEqualTo(ErrorCode.OPTIMISTIC_LOCK);
        }

        @Test
        @DisplayName("a lost DELETE race is OPTIMISTIC_LOCK")
        void lostDelete() {
            when(conversions.find(SKU.code(), CARTON_ID)).thenReturn(Optional.of(unit(CARTON_ID, "CARTON", 12, false,
                    false, false, 1)));
            when(conversions.delete(CARTON_ID, 1)).thenReturn(false);

            assertThat(codeOf(() -> service.remove(SKU, CARTON_ID, 1))).isEqualTo(ErrorCode.OPTIMISTIC_LOCK);
            verify(conversions, never()).defaultToBaseIfNone(any(), anyString());
        }

        @Test
        @DisplayName("an unused unit is deleted, and the base unit becomes the default purchase unit if none is left")
        void deletedThenDefaultsToBase() {
            when(conversions.find(SKU.code(), CARTON_ID)).thenReturn(Optional.of(unit(CARTON_ID, "CARTON", 12, false,
                    true, false, 1)));
            when(conversions.delete(CARTON_ID, 1)).thenReturn(true);

            service.remove(SKU, CARTON_ID, 1);

            InOrder order = inOrder(items, conversions);
            order.verify(items).lock(SKU.code());
            order.verify(conversions).delete(CARTON_ID, 1);
            order.verify(conversions).defaultToBaseIfNone(ITEM, "system");
        }
    }
}
