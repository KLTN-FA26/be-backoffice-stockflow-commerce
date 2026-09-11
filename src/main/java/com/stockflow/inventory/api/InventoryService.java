package com.stockflow.inventory.api;

import com.stockflow.common.domain.Sku;

import java.util.List;
import java.util.UUID;

/**
 * THE public API of the inventory module. Every other module talks to inventory through this
 * interface and nothing else.
 *
 * <p>What other modules deliberately cannot reach: {@code StockItem}, {@code StockItemRepository},
 * the JPA entities, the controller. They all live under {@code inventory.internal}, and
 * {@code ModularityTest} fails the build if anyone imports them.</p>
 *
 * <p>The parameters and return types are records declared in this same package, never domain
 * objects. Handing another module a {@code StockItem} would let it call {@code reserve()} on the
 * aggregate directly, outside a transaction and outside the invariants.</p>
 */
public interface InventoryService {

    /**
     * Available to promise for one SKU, summed over every location holding sellable stock.
     * {@code ATP = on_hand − reserved − allocated}, counting only stock in condition GOOD.
     */
    int availableToPromise(Sku sku);

    /** Per-location breakdown, earliest expiry first (FEFO). Used by fulfilment when allocating. */
    List<StockAvailability> availabilityOf(Sku sku);

    /**
     * Reserve stock for an order line.
     *
     * <p>Called synchronously from {@code order} during checkout, <b>inside the caller's
     * transaction</b>. That is the whole point of the monolith: if the order row fails to persist,
     * this reservation rolls back with it. In the microservices version this same step needed a
     * saga plus a compensating action plus a reservation timeout.</p>
     *
     * @throws com.stockflow.common.error.BusinessException when ATP is below the requested quantity
     */
    ReserveStockResult reserve(ReserveStockCommand command);

    /** Release a reservation — order cancelled, payment failed, or the hold expired. */
    void release(UUID reservationId, String reason);
}
