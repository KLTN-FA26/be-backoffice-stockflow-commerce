package com.stockflow.contracts;

import java.time.Instant;
import java.util.UUID;

/**
 * Published by the <b>inventory</b> module when a {@code DAMAGED} write-off of a carton of a production order's lot is
 * approved (kltn-docs 20 §4.8 step 3, 17 §4.13 step 51): the cartons that came back printed from a producer and turned
 * out faulty after they were received. The <b>notification</b> module tells the Production Coordinator, who draws up
 * the replacement production order if a sales order is left short by it. Once per approved adjustment.
 *
 * <p>Facts only; the sales order left short is worked out by whoever is told, when the message is written, from the
 * production order. Consumers receive it in-process through {@code @ApplicationModuleListener}; see
 * {@code package-info.java} for the rules on changing this contract.</p>
 *
 * @param adjustmentId       the approved write-off
 * @param adjustmentNumber   its number, as the adjustment screens show it
 * @param stockPackageId     the carton written off
 * @param lpn                its LPN
 * @param sku                its SKU
 * @param lotNumber          its lot: the production order's code
 * @param productionOrderId  the production order whose goods it was
 * @param warehouseId        the warehouse the carton stood in
 * @param approvedBy         who approved the write-off
 */
public record ProductionCartonWrittenOffDamaged(UUID eventId, UUID adjustmentId, String adjustmentNumber,
                                                UUID stockPackageId, String lpn, String sku, String lotNumber,
                                                UUID productionOrderId, UUID warehouseId, UUID approvedBy,
                                                Instant approvedAt) {
}
