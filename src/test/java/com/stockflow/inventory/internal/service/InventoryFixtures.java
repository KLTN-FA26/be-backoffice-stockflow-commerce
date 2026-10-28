package com.stockflow.inventory.internal.service;

import com.stockflow.common.domain.Sku;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.inventory.internal.domain.LocationId;
import com.stockflow.inventory.internal.domain.Quantity;
import com.stockflow.inventory.internal.domain.StockItem;
import com.stockflow.inventory.internal.domain.StockItemId;
import com.stockflow.inventory.internal.domain.StockStatus;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/** Builders shared by the inventory service unit tests. */
final class InventoryFixtures {

    static final Instant NOW = Instant.parse("2026-10-22T03:00:00Z");
    static final LocalDate TODAY = LocalDate.of(2026, 10, 22);
    static final Sku SKU = new Sku("CUP-12OZ");

    private static final AtomicInteger SEQ = new AtomicInteger();

    private InventoryFixtures() {
    }

    /** A sellable (AVAILABLE) row holding {@code onHand} units at {@code location}, received a minute apart. */
    static StockItem available(String sku, String location, String lot, LocalDate expiry, int onHand) {
        StockItem item = new StockItem(StockItemId.newId(), new Sku(sku), new LocationId(location), lot, expiry,
                Quantity.of(onHand), StockStatus.QUARANTINE, List.of(), 0L);
        item.releaseFromQuarantine();
        item.pullDomainEvents();
        return item;
    }

    /** As above, with the receipt time a FIFO policy ranks by. */
    static StockItem availableReceived(String sku, String location, String lot, LocalDate expiry, int onHand,
                                       Instant receivedAt) {
        return new StockItem(StockItemId.newId(), new Sku(sku), new LocationId(location), lot, expiry,
                Quantity.of(onHand), StockStatus.AVAILABLE, List.of(), 0L, receivedAt, null);
    }

    static StockItem available(String location, int onHand) {
        return available(SKU.code(), location, null, null, onHand);
    }

    static UUID id() {
        return new UUID(0L, SEQ.incrementAndGet());
    }

    /** The error code a call is refused with; fails when it is not refused with a {@link BusinessException}. */
    static ErrorCode codeOf(Runnable call) {
        BusinessException thrown = catchThrowableOfType(BusinessException.class, call::run);
        assertThat(thrown).as("a BusinessException").isNotNull();
        return thrown.errorCode();
    }
}
