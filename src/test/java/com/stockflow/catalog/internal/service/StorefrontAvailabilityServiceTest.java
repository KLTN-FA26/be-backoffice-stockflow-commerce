package com.stockflow.catalog.internal.service;

import com.stockflow.catalog.internal.domain.Availability;
import com.stockflow.catalog.internal.domain.SkuAvailability;
import com.stockflow.catalog.internal.repository.ListingRepository;
import com.stockflow.common.domain.Sku;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.inventory.api.InventoryService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import com.stockflow.customer.api.CustomerService;
import com.stockflow.customer.api.CustomerSummary;
import java.util.Optional;
import java.util.UUID;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class StorefrontAvailabilityServiceTest {

    private static final Sku SOFA = new Sku("SOFA-3S-GREY");
    private static final Sku TABLE = new Sku("TABLE-OAK-160");
    private static final Sku CHAIR = new Sku("CHAIR-01");

    private final UUID userId = UUID.randomUUID();
    private final CustomerService customers = mock(CustomerService.class);
    private final InventoryService inventory = mock(InventoryService.class);
    private final ListingRepository listings = mock(ListingRepository.class);
    private final StorefrontAvailabilityService service =
            new StorefrontAvailabilityService(inventory, new AvailabilityProperties(5, 3), customers, listings);

    private static ListingRepository.ListedSku listed(Sku sku) {
        return new ListingRepository.ListedSku(sku.code(), UUID.randomUUID(), "slug", "Title");
    }

    @BeforeEach
    void activeCustomer() {
        when(customers.findByUserId(userId)).thenReturn(Optional.of(new CustomerSummary(
                UUID.randomUUID(), userId, "Customer", "customer@example.test", null, null, null, "ACTIVE", 0, null)));
        // By default every SKU used below is on sale.
        when(listings.listedBySku(any())).thenAnswer(call -> {
            Collection<String> codes = call.getArgument(0);
            Map<String, ListingRepository.ListedSku> all = new java.util.HashMap<>();
            codes.forEach(code -> all.put(code, listed(new Sku(code))));
            return all;
        });
    }

    @Test
    void oneAnswerPerDistinctSkuInTheOrderAsked() {
        when(inventory.availableToPromise(any(Collection.class)))
                .thenReturn(Map.of(SOFA, 33, TABLE, 4, CHAIR, 0));

        List<SkuAvailability> result = service.availabilityOf(userId,
                List.of("table-oak-160", "SOFA-3S-GREY", " CHAIR-01 ", "TABLE-OAK-160"));

        assertThat(result).containsExactly(
                new SkuAvailability(TABLE, Availability.LOW_STOCK),
                new SkuAvailability(SOFA, Availability.IN_STOCK),
                new SkuAvailability(CHAIR, Availability.OUT_OF_STOCK));
        // One call for the whole page, with the duplicate already collapsed.
        verify(inventory).availableToPromise(new LinkedHashSet<>(List.of(TABLE, SOFA, CHAIR)));
    }

    @Test
    void aSkuInventoryDoesNotMentionIsOutOfStock() {
        when(inventory.availableToPromise(any(Collection.class))).thenReturn(Map.of());

        assertThat(service.availabilityOf(userId, List.of("SOFA-3S-GREY")))
                .containsExactly(new SkuAvailability(SOFA, Availability.OUT_OF_STOCK));
    }

    @Test
    void unpublishedOrInternalSkuIsOmittedAndNeverReachesInventory() {
        doReturn(Map.of()).when(listings).listedBySku(any());

        assertThat(service.availabilityOf(userId, List.of("SOFA-3S-GREY", "INTERNAL-BLANK"))).isEmpty();
        verifyNoInteractions(inventory);
    }

    @Test
    void mixedRequestAnswersOnlyThePublishedSkusInTheOrderAsked() {
        doReturn(Map.of(SOFA.code(), listed(SOFA), CHAIR.code(), listed(CHAIR))).when(listings).listedBySku(any());
        when(inventory.availableToPromise(any(Collection.class))).thenReturn(Map.of(SOFA, 33, CHAIR, 0, TABLE, 9));

        List<SkuAvailability> result = service.availabilityOf(userId, List.of("CHAIR-01", "TABLE-OAK-160", "SOFA-3S-GREY"));

        assertThat(result).containsExactly(
                new SkuAvailability(CHAIR, Availability.OUT_OF_STOCK),
                new SkuAvailability(SOFA, Availability.IN_STOCK));
        verify(inventory).availableToPromise(new LinkedHashSet<>(List.of(CHAIR, SOFA)));
    }

    @Test
    void refusedBeforeReachingInventory() {
        List<String> tooMany = new ArrayList<>(IntStream.rangeClosed(1, 4).mapToObj(i -> "SKU-00" + i).toList());

        for (List<String> bad : List.of(List.<String>of(), List.of("bad sku!"), tooMany)) {
            assertThatThrownBy(() -> service.availabilityOf(userId, bad))
                    .isInstanceOfSatisfying(BusinessException.class,
                            ex -> assertThat(ex.errorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED));
        }
        assertThatThrownBy(() -> service.availabilityOf(userId, null)).isInstanceOf(BusinessException.class);
        verifyNoInteractions(inventory);
        verifyNoInteractions(listings);
    }
    @Test
    void missingOrBlockedCustomerCannotQueryInventory() {
        when(customers.findByUserId(userId)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.availabilityOf(userId, List.of("TABLE-OAK-160")))
                .isInstanceOfSatisfying(BusinessException.class,
                        ex -> assertThat(ex.errorCode()).isEqualTo(ErrorCode.FORBIDDEN));
        for (String status : List.of("PENDING_ACTIVATION", "INACTIVE", "BLOCKED")) {
            when(customers.findByUserId(userId)).thenReturn(Optional.of(new CustomerSummary(
                    UUID.randomUUID(), userId, "Customer", "customer@example.test", null, null, null, status, 0, null)));
            assertThatThrownBy(() -> service.availabilityOf(userId, List.of("TABLE-OAK-160")))
                    .isInstanceOfSatisfying(BusinessException.class,
                            ex -> assertThat(ex.errorCode()).isEqualTo(ErrorCode.FORBIDDEN));
        }
        verifyNoInteractions(inventory);
    }

    @Test
    void nullSubjectCannotQueryInventory() {
        assertThatThrownBy(() -> service.availabilityOf(null, List.of("TABLE-OAK-160")))
                .isInstanceOfSatisfying(BusinessException.class,
                        ex -> assertThat(ex.errorCode()).isEqualTo(ErrorCode.UNAUTHORIZED));
        verifyNoInteractions(inventory);
    }

}
