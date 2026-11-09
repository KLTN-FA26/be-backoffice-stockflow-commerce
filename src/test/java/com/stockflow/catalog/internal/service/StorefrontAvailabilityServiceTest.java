package com.stockflow.catalog.internal.service;

import com.stockflow.catalog.internal.domain.Availability;
import com.stockflow.catalog.internal.domain.SkuAvailability;
import com.stockflow.common.domain.Sku;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.customer.api.CustomerService;
import com.stockflow.customer.api.CustomerSummary;
import com.stockflow.inventory.api.InventoryService;
import com.stockflow.inventory.api.UomConversion;
import com.stockflow.inventory.api.UomConversionService;
import com.stockflow.product.api.CustomerSku;
import com.stockflow.product.api.CustomerSkus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 5-3 (kltn-docs 13 §4.4 step 4, BR-02, BR-07, BR-11, BR-12): the indicator answers for the caller's own ACTIVE SKUs
 * only, counted in whole cartons, against a threshold in cartons.
 */
class StorefrontAvailabilityServiceTest {

    /** The caller's own SKUs: cartons of 10, 24 and 50. */
    private static final Sku CUP = new Sku("LY12-ACME-01");
    private static final Sku BAG = new Sku("TUI-ACME-01");
    private static final Sku LID = new Sku("NAP-ACME-01");
    /** The caller's own, retired. */
    private static final Sku OLD = new Sku("LY12-ACME-00");
    /** Another customer's SKU, and a variant (quy cách) nobody owns. */
    private static final Sku THEIRS = new Sku("LY12-OTHER-01");
    private static final Sku VARIANT = new Sku("LY12-TRANG");

    private final UUID userId = UUID.randomUUID();
    private final UUID customerId = UUID.randomUUID();
    private final CustomerService customers = mock(CustomerService.class);
    private final InventoryService inventory = mock(InventoryService.class);
    private final UomConversionService units = mock(UomConversionService.class);
    private final CustomerSkus customerSkus = mock(CustomerSkus.class);
    private final StorefrontAvailabilityService service = new StorefrontAvailabilityService(inventory, units,
            new AvailabilityProperties(5, 6), customers, customerSkus);
    private final Map<String, List<UomConversion>> declared = new HashMap<>();

    @BeforeEach
    void activeCustomerWithItsOwnSkus() {
        when(customers.findByUserId(userId)).thenReturn(Optional.of(new CustomerSummary(
                customerId, userId, "Customer", "customer@example.test", null, null, null, "ACTIVE", 0, null)));
        when(customerSkus.ownedBy(customerId, null)).thenReturn(List.of(own(CUP, "ACTIVE"), own(BAG, "ACTIVE"),
                own(LID, "ACTIVE"), own(OLD, "OBSOLETE")));
        declared.put(CUP.code(), List.of(unit(CUP, "PCS", 1, true, true, false), unit(CUP, "CARTON", 10, false, true, false)));
        declared.put(BAG.code(), List.of(unit(BAG, "PCS", 1, true, true, false), unit(BAG, "CARTON", 24, false, true, false)));
        // Two cartons: the default shipping one is the SKU's carton; a retired one is never it.
        declared.put(LID.code(), List.of(unit(LID, "PCS", 1, true, true, false), unit(LID, "BOX", 20, false, true, false),
                unit(LID, "CARTON", 50, false, true, true), unit(LID, "OLD", 100, false, false, false)));
        when(units.conversionsOf(any())).thenAnswer(call -> {
            Collection<Sku> skus = call.getArgument(0);
            Map<String, List<UomConversion>> found = new HashMap<>();
            skus.forEach(sku -> {
                if (declared.containsKey(sku.code())) found.put(sku.code(), declared.get(sku.code()));
            });
            return found;
        });
    }

    @Test
    void wholeCartonsAgainstAThresholdInCartonsInTheOrderAsked() {
        // CUP: 51 pieces = 5 cartons (at the threshold); BAG: 6 x 24 = 6 cartons; LID: 49 pieces is not one carton.
        when(inventory.availableToPromise(any(Collection.class))).thenReturn(Map.of(CUP, 51, BAG, 144, LID, 49));

        List<SkuAvailability> result = service.availabilityOf(userId,
                List.of("tui-acme-01", "LY12-ACME-01", " NAP-ACME-01 ", "TUI-ACME-01"));

        assertThat(result).containsExactly(
                new SkuAvailability(BAG, Availability.IN_STOCK),
                new SkuAvailability(CUP, Availability.LOW_STOCK),
                new SkuAvailability(LID, Availability.OUT_OF_STOCK));
        // One call for the whole page, with the duplicate already collapsed.
        verify(inventory).availableToPromise(new LinkedHashSet<>(List.of(BAG, CUP, LID)));
    }

    @Test
    void aSkuInventoryDoesNotMentionIsOutOfStock() {
        when(inventory.availableToPromise(any(Collection.class))).thenReturn(Map.of());

        assertThat(service.availabilityOf(userId, List.of(CUP.code())))
                .containsExactly(new SkuAvailability(CUP, Availability.OUT_OF_STOCK));
    }

    @Test
    void anotherCustomersSkuAVariantAndARetiredSkuAreNotThereAndNeverReachInventory() {
        assertThat(service.availabilityOf(userId, List.of(THEIRS.code(), VARIANT.code(), OLD.code()))).isEmpty();
        verifyNoInteractions(inventory);
        verifyNoInteractions(units);
    }

    @Test
    void aMixedRequestAnswersOnlyTheCallersOwnSkus() {
        when(inventory.availableToPromise(any(Collection.class))).thenReturn(Map.of(CUP, 1_000, THEIRS, 1_000));

        assertThat(service.availabilityOf(userId, List.of(THEIRS.code(), CUP.code(), VARIANT.code())))
                .containsExactly(new SkuAvailability(CUP, Availability.IN_STOCK));
        verify(inventory).availableToPromise(new LinkedHashSet<>(List.of(CUP)));
    }

    @Test
    void aSkuWithoutASingleCartonGetsNoIndicatorRatherThanAGuess() {
        // No carton at all, and two cartons none of which is the default shipping one.
        declared.put(CUP.code(), List.of(unit(CUP, "PCS", 1, true, true, false)));
        declared.put(BAG.code(), List.of(unit(BAG, "PCS", 1, true, true, false), unit(BAG, "BOX", 12, false, true, false),
                unit(BAG, "CARTON", 24, false, true, false)));
        when(inventory.availableToPromise(any(Collection.class))).thenReturn(Map.of(LID, 500));

        assertThat(service.availabilityOf(userId, List.of(CUP.code(), BAG.code(), LID.code())))
                .containsExactly(new SkuAvailability(LID, Availability.IN_STOCK));
        verify(inventory).availableToPromise(new LinkedHashSet<>(List.of(LID)));
    }

    @Test
    void refusedBeforeReachingInventory() {
        List<String> tooMany = new ArrayList<>(IntStream.rangeClosed(1, 7).mapToObj(i -> "SKU-00" + i).toList());

        for (List<String> bad : List.of(List.<String>of(), List.of("bad sku!"), tooMany)) {
            assertThatThrownBy(() -> service.availabilityOf(userId, bad))
                    .isInstanceOfSatisfying(BusinessException.class,
                            ex -> assertThat(ex.errorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED));
        }
        assertThatThrownBy(() -> service.availabilityOf(userId, null)).isInstanceOf(BusinessException.class);
        verifyNoInteractions(inventory);
        verifyNoInteractions(units);
    }

    @Test
    void missingOrBlockedCustomerCannotQueryInventory() {
        when(customers.findByUserId(userId)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.availabilityOf(userId, List.of(CUP.code())))
                .isInstanceOfSatisfying(BusinessException.class,
                        ex -> assertThat(ex.errorCode()).isEqualTo(ErrorCode.FORBIDDEN));
        for (String status : List.of("PENDING_ACTIVATION", "INACTIVE", "BLOCKED")) {
            when(customers.findByUserId(userId)).thenReturn(Optional.of(new CustomerSummary(
                    customerId, userId, "Customer", "customer@example.test", null, null, null, status, 0, null)));
            assertThatThrownBy(() -> service.availabilityOf(userId, List.of(CUP.code())))
                    .isInstanceOfSatisfying(BusinessException.class,
                            ex -> assertThat(ex.errorCode()).isEqualTo(ErrorCode.FORBIDDEN));
        }
        verifyNoInteractions(inventory);
        verifyNoInteractions(customerSkus);
    }

    @Test
    void nullSubjectCannotQueryInventory() {
        assertThatThrownBy(() -> service.availabilityOf(null, List.of(CUP.code())))
                .isInstanceOfSatisfying(BusinessException.class,
                        ex -> assertThat(ex.errorCode()).isEqualTo(ErrorCode.UNAUTHORIZED));
        verifyNoInteractions(inventory);
    }

    private CustomerSku own(Sku sku, String status) {
        return new CustomerSku(UUID.randomUUID(), UUID.randomUUID(), sku.code(), sku.code(), customerId,
                UUID.randomUUID(), "LY12-TRANG", status);
    }

    private static UomConversion unit(Sku sku, String code, int factor, boolean base, boolean active,
                                      boolean defaultShipping) {
        return new UomConversion(UUID.randomUUID(), sku.code(), code, factor, base, BigDecimal.ONE, null, null, null,
                base, active, 0, defaultShipping);
    }
}
