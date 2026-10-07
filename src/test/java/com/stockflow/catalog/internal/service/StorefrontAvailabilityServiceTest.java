package com.stockflow.catalog.internal.service;

import com.stockflow.catalog.internal.domain.Availability;
import com.stockflow.catalog.internal.domain.SkuAvailability;
import com.stockflow.common.domain.Sku;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.inventory.api.InventoryService;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class StorefrontAvailabilityServiceTest {

    private static final Sku SOFA = new Sku("SOFA-3S-GREY");
    private static final Sku TABLE = new Sku("TABLE-OAK-160");
    private static final Sku CHAIR = new Sku("CHAIR-01");

    private final InventoryService inventory = mock(InventoryService.class);
    private final StorefrontAvailabilityService service =
            new StorefrontAvailabilityService(inventory, new AvailabilityProperties(5, 3));

    @Test
    void oneAnswerPerDistinctSkuInTheOrderAsked() {
        when(inventory.availableToPromise(any(Collection.class)))
                .thenReturn(Map.of(SOFA, 33, TABLE, 4, CHAIR, 0));

        List<SkuAvailability> result = service.availabilityOf(
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

        assertThat(service.availabilityOf(List.of("SOFA-3S-GREY")))
                .containsExactly(new SkuAvailability(SOFA, Availability.OUT_OF_STOCK));
    }

    @Test
    void refusedBeforeReachingInventory() {
        List<String> tooMany = new ArrayList<>(IntStream.rangeClosed(1, 4).mapToObj(i -> "SKU-00" + i).toList());

        for (List<String> bad : List.of(List.<String>of(), List.of("bad sku!"), tooMany)) {
            assertThatThrownBy(() -> service.availabilityOf(bad))
                    .isInstanceOfSatisfying(BusinessException.class,
                            ex -> assertThat(ex.errorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED));
        }
        assertThatThrownBy(() -> service.availabilityOf(null)).isInstanceOf(BusinessException.class);
        verifyNoInteractions(inventory);
    }
}
