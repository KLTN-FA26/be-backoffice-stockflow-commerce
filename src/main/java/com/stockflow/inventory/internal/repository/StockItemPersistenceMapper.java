package com.stockflow.inventory.internal.repository;

import com.stockflow.inventory.internal.entity.ReservationJpaEntity;
import com.stockflow.inventory.internal.entity.StockItemJpaEntity;

import com.stockflow.inventory.internal.domain.LocationId;
import com.stockflow.inventory.internal.domain.Quantity;
import com.stockflow.inventory.internal.domain.Reservation;
import com.stockflow.inventory.internal.domain.ReservationId;
import com.stockflow.inventory.internal.domain.StockItem;
import com.stockflow.inventory.internal.domain.StockItemId;
import com.stockflow.common.domain.Sku;

import java.util.List;

/**
 * Translates between the aggregate and its rows.
 *
 * <p>Hand-written rather than MapStruct on purpose. The mapping is not field-for-field: the
 * aggregate exposes {@code reserved()} as a computed sum while the table stores it as a column,
 * and rehydration has to go through the full domain constructor so invariants are re-checked. A
 * generated mapper would need so many {@code @Mapping} overrides that the annotations would be
 * longer than this class — and it would quietly bypass the constructor checks.</p>
 *
 * <p>MapStruct is still the right tool for the flat DTO mapping in {@code internal.controller}, where the
 * shapes really do line up.</p>
 */
final class StockItemPersistenceMapper {

    private StockItemPersistenceMapper() {
    }

    static StockItem toDomain(StockItemJpaEntity entity) {
        List<Reservation> reservations = entity.getReservations().stream()
                .map(StockItemPersistenceMapper::toDomain)
                .toList();

        return new StockItem(
                new StockItemId(entity.getId()),
                new Sku(entity.getSku()),
                new LocationId(entity.getLocationCode()),
                entity.getLotNumber(),
                entity.getExpiryDate(),
                Quantity.of(entity.getOnHand()),
                entity.getStatus(),
                reservations,
                entity.getVersion());
    }

    static Reservation toDomain(ReservationJpaEntity entity) {
        return new Reservation(
                new ReservationId(entity.getId()),
                entity.getOrderId(),
                entity.getRootRequestId(),
                entity.getRequestId(),
                Quantity.of(entity.getQuantity()),
                entity.getReservedAt(),
                entity.getExpiresAt(),
                entity.getStatus(),
                entity.getReleaseReason(),
                entity.getClosedAt());
    }

    /** Fresh row for an aggregate that has never been persisted. */
    static StockItemJpaEntity toNewEntity(StockItem item) {
        StockItemJpaEntity entity = new StockItemJpaEntity(
                item.id().value(),
                item.sku().code(),
                item.location().code(),
                item.lotNumber(),
                item.expiryDate(),
                item.onHand().value(),
                item.reserved().value(),
                item.status());
        entity.replaceReservations(toEntities(item));
        return entity;
    }

    /**
     * Copy the aggregate's state onto a row already managed by the persistence context, so
     * Hibernate's dirty checking writes the UPDATE. Replacing the whole child collection is safe
     * here because a stock item holds a bounded number of live reservations, and it keeps
     * {@code reserved} in lockstep with the children by construction.
     */
    static void applyToEntity(StockItem item, StockItemJpaEntity entity) {
        entity.apply(item.onHand().value(), item.reserved().value(), item.status());
        entity.replaceReservations(toEntities(item));
    }

    private static List<ReservationJpaEntity> toEntities(StockItem item) {
        return item.reservations().stream()
                .map(r -> new ReservationJpaEntity(
                        r.id().value(),
                        r.orderId(),
                        r.rootRequestId(),
                        r.requestId(),
                        r.quantity().value(),
                        r.reservedAt(),
                        r.expiresAt(),
                        r.status(),
                        r.releaseReason(),
                        r.closedAt()))
                .toList();
    }
}
