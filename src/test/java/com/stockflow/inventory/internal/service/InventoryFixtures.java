package com.stockflow.inventory.internal.service;

import com.stockflow.common.domain.Sku;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.inventory.internal.domain.LocationId;
import com.stockflow.inventory.internal.domain.StockPackage;
import com.stockflow.inventory.internal.domain.StockPackageId;
import com.stockflow.inventory.internal.domain.StockPackageStatus;
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

    /**
     * A sellable (AVAILABLE) carton of {@code units} in stock at {@code location}, held by no order, of a lot of its
     * own numbered {@code lot} ({@code UNTRACED} when null); its receipt time is not known.
     */
    static StockPackage available(String sku, String location, String lot, int units) {
        return availableReceived(sku, location, lot, units, null);
    }

    /** As above, with the receipt time FIFO ranks by. */
    static StockPackage availableReceived(String sku, String location, String lot, int units, Instant receivedAt) {
        return carton(sku, location, lot, units, receivedAt, StockStatus.AVAILABLE);
    }

    /** A carton of {@code units} in stock at {@code location} in stock status {@code status}. */
    static StockPackage carton(String sku, String location, String lot, int units, Instant receivedAt,
                               StockStatus status) {
        return new StockPackage(new StockPackageId(UUID.randomUUID()), "LPN-T-%06d".formatted(SEQ.incrementAndGet()),
                new Sku(sku), UUID.randomUUID(), UUID.randomUUID(), units, NOW, new LocationId(location),
                StockPackageStatus.IN_STOCK, status, UUID.randomUUID(), lot == null ? "UNTRACED" : lot, receivedAt,
                null, null, List.of(), 0L);
    }

    static StockPackage available(String location, int units) {
        return available(SKU.code(), location, null, units);
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
