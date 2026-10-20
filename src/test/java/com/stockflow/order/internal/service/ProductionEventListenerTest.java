package com.stockflow.order.internal.service;

import com.stockflow.common.domain.Sku;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.contracts.SampleApproved;
import com.stockflow.inventory.api.InventoryItemPolicy;
import com.stockflow.inventory.api.InventoryService;
import com.stockflow.order.internal.domain.ProductionRecords;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** The blank of an approved sample is an inventory item (kltn-docs glossary "Phôi / Blank"). */
class ProductionEventListenerTest {

    private final ProductionRecords records = mock(ProductionRecords.class);
    private final InventoryService inventory = mock(InventoryService.class);
    private final Clock clock = Clock.fixed(Instant.parse("2026-10-16T03:00:00Z"), ZoneOffset.UTC);
    private final ProductionEventListener listener =
            new ProductionEventListener(records, mock(OrderReleaseService.class), clock, inventory);

    @Test
    void aBlankWithoutAnInventoryItemIsRefusedByName() {
        when(inventory.itemPolicy(new Sku("NO-ITEM"))).thenReturn(Optional.empty());
        assertThatThrownBy(() -> listener.on(new SampleApproved(UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), "NO-ITEM"))).isInstanceOfSatisfying(BusinessException.class,
                e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.INVENTORY_ITEM_NOT_FOUND));
        verify(records, never()).recordSampleApproved(any(), any(), any(), anyString(), any());
    }

    @Test
    void aKnownBlankIsRecorded() {
        UUID sample = UUID.randomUUID();
        when(inventory.itemPolicy(new Sku("CUP-12OZ"))).thenReturn(Optional.of(
                new InventoryItemPolicy(UUID.randomUUID(), "CUP-12OZ", false, false, false)));
        listener.on(new SampleApproved(sample, UUID.randomUUID(), UUID.randomUUID(), "CUP-12OZ"));
        verify(records).recordSampleApproved(any(), any(), any(), anyString(), any());
    }
}
