package com.stockflow.contracts;

import java.util.UUID;

/**
 * Published by the <b>inventory</b> module when a warehouse manager decides what happens to some units of a damage
 * report (docs 03 §5 DAMAGED → written off). One event per decision. See {@code package-info.java} for the rules
 * on changing this contract.
 *
 * <p>{@code RETURN_TO_SUPPLIER} is where a supplier return starts: inventory records the intent and keeps the units
 * DAMAGED in quarantine until a manager records them handed over under a delivery note
 * ({@code POST /api/v1/inventory/damage-reports/{id}/dispositions/{dispositionId}/supplier-shipment}), when they
 * leave the books. A procurement return flow listens to this event to raise the debit note.</p>
 *
 * @param disposition  {@code WRITE_OFF}, {@code RETURN_TO_SUPPLIER} or {@code RESTORE}
 * @param recordNumber the destruction record of a {@code WRITE_OFF}; null otherwise
 * @param supplierId   the supplier the goods go back to, when known (from their lot); {@code RETURN_TO_SUPPLIER} only
 * @param restoreLocationCode the bin restored goods went back onto; {@code RESTORE} only
 */
public record StockDamageDisposed(
        UUID reportId,
        String reportNumber,
        UUID dispositionId,
        String disposition,
        int quantity,
        String recordNumber,
        UUID warehouseId,
        String sku,
        String lotNumber,
        String damageLocationCode,
        UUID supplierId,
        String restoreLocationCode
) {
}
