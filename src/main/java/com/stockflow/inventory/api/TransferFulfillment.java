package com.stockflow.inventory.api;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * What fulfillment needs of a transfer order to pack it, plan its trip and dispatch it (SCRUM-287 PR T; kltn-docs 10
 * §4 step 5, §5, BR-04; lead's decisions LP-9, LP2-8). The transfer stays inventory's: fulfillment reads it and asks it
 * to move, it never changes the transfer's rows itself.
 *
 * <p>Every call joins the caller's transaction. Picking, packing and dispatch happen on the source warehouse's floor:
 * the changing calls are refused ({@code OUT_OF_DATA_SCOPE}) to a caller not bound to the source warehouse.</p>
 */
public interface TransferFulfillment {

    /** One line of the transfer: what is requested, and what left the source at dispatch. */
    record Line(UUID lineId, int lineNo, String sku, String lotNumber, int requested, int shipped) {
    }

    /**
     * A transfer as fulfillment sees it.
     *
     * @param status      the transfer's status name (kltn-docs 10 §5: APPROVED, PICKING, IN_TRANSIT ...)
     * @param packedAt    its packing completed: ready for a load plan; null before, and again after picking is cancelled
     * @param goodsLeftAt goods of it first left on a load plan's vehicle (from then picking is not cancelled)
     */
    record TransferView(UUID transferId, String number, UUID fromWarehouseId, UUID toWarehouseId, String status,
                        TransferDeliveryMode deliveryMode, Instant packedAt, Instant goodsLeftAt, Instant dispatchedAt,
                        List<Line> lines) {

        public boolean picking() {
            return "PICKING".equals(status);
        }
    }

    /**
     * An unopened stock carton (a stock package, #129) that may ship as it is (LP2-8).
     *
     * @param quantity        base units in it now (an unopened carton holds exactly what was counted into it)
     * @param atWarehouse     it stands in the warehouse asked about
     * @param uomConversionId the packing unit it is a carton of: its size and weight pre-fill the parcel's measures
     * @param sellable        every unit in it is AVAILABLE and none is reserved: it may leave whole
     */
    record Carton(UUID stockPackageId, String lpn, String sku, String lotNumber, int quantity, boolean atWarehouse,
                  String locationCode, UUID uomConversionId, String unitOfMeasure, boolean unopened,
                  boolean inStock, boolean sellable) {
    }

    Optional<TransferView> find(UUID transferId);

    List<TransferView> findAll(Collection<UUID> transferIds);

    /**
     * Packing starts at the source (10 step 4-5): an APPROVED transfer starts picking; one PICKING already is answered
     * as it is.
     *
     * @throws com.stockflow.common.error.BusinessException {@code TRANSFER_NOT_FULL_TRUCKLOAD} for a PARCEL transfer;
     *         {@code INVALID_TRANSFER_TRANSITION} in any other status; {@code OUT_OF_DATA_SCOPE}
     */
    TransferView beginPacking(UUID transferId);

    /** Its packing completed: ready for a load plan. PICKING only. */
    TransferView markPacked(UUID transferId, Instant at);

    /** Goods of it left on a load plan's vehicle; the first time is kept. PICKING only. */
    TransferView recordGoodsLeft(UUID transferId, Instant at);

    /**
     * The last vehicle signed (10 step 5, 09 §4.2 step 5): the transfer is dispatched with the packed quantities -
     * the stock leaves the source and is In-Transit, owned by the transfer. The units of an unopened carton shipped as
     * it is ({@code cartonsByLine}: line id to stock package ids) are taken from that carton; the rest by the item's
     * removal policy, as a manual dispatch does.
     *
     * @throws com.stockflow.common.error.BusinessException {@code INVALID_TRANSFER_TRANSITION} unless PICKING and
     *         packed; {@code INSUFFICIENT_STOCK} when the source no longer holds the units
     */
    TransferView dispatchLoaded(UUID transferId, UUID userId, Map<UUID, Integer> shippedByLine,
                                Map<UUID, List<UUID>> cartonsByLine);

    /** The stock carton labelled {@code lpn}, if there is one, and whether it stands in {@code warehouseId}. */
    Optional<Carton> carton(String lpn, UUID warehouseId);
}
