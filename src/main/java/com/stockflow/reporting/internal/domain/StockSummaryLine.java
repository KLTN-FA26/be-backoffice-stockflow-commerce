package com.stockflow.reporting.internal.domain;

import java.util.UUID;

/**
 * One SKU in one warehouse over the period of the stock summary (2026-11-14 plan, BE2-C; kltn-docs 20 §4.7): opening,
 * what came in and went out by kind, closing - in pieces and in cartons.
 *
 * <p>Cartons are pieces divided by the SKU's standard count per carton. Stock is only ever whole cartons (20 BR-14,
 * 03 BR-13), so the division is exact; when it is not - the SKU has no carton unit to count by, or the ledger holds
 * history from before cartons (loose stock boxed by V20261111000110) - the cartons are left empty rather than rounded,
 * and {@link #cartonNote()} says why.</p>
 *
 * @param warehouseId    null for a warehouse that is no longer active (the ledger keeps its lines)
 * @param ownerId        the customer whose own SKU it is (12 §4.5); only customers' SKUs hold stock
 * @param unitsPerCarton the SKU's standard count per carton; null when it has no carton unit
 */
public record StockSummaryLine(UUID warehouseId, String warehousePrefix, String warehouseName, String sku,
                               UUID ownerId, String ownerCode, String ownerName, Integer unitsPerCarton,
                               long openingUnits, long receivedUnits, long returnedUnits, long putBackUnits,
                               long pickedUnits, long adjustedInUnits, long adjustedOutUnits, long otherInUnits,
                               long otherOutUnits, long closingUnits) {

    /** Why the cartons of a line are not counted. */
    public enum CartonNote {
        /** Every column is a whole number of cartons. */
        NONE,
        /** The SKU has no carton unit (kltn-docs 01 BR-03) to count cartons by. */
        NO_CARTON_UNIT,
        /** Some column is not a whole number of cartons. */
        NOT_WHOLE_CARTONS
    }

    /** {@code units} in cartons of this SKU; null when it has no carton unit or the division is not exact. */
    public Long cartons(long units) {
        if (unitsPerCarton == null || unitsPerCarton <= 0 || units % unitsPerCarton != 0) {
            return null;
        }
        return units / unitsPerCarton;
    }

    /** Why some column has no cartons, or {@link CartonNote#NONE}. */
    public CartonNote cartonNote() {
        if (unitsPerCarton == null || unitsPerCarton <= 0) {
            return CartonNote.NO_CARTON_UNIT;
        }
        for (long units : new long[]{openingUnits, receivedUnits, returnedUnits, putBackUnits, pickedUnits,
                adjustedInUnits, adjustedOutUnits, otherInUnits, otherOutUnits, closingUnits}) {
            if (units % unitsPerCarton != 0) {
                return CartonNote.NOT_WHOLE_CARTONS;
            }
        }
        return CartonNote.NONE;
    }

    /** The columns add up: opening plus what came in less what went out is the closing (the ledger's own sum). */
    public boolean balances() {
        return openingUnits + receivedUnits + returnedUnits + putBackUnits - pickedUnits + adjustedInUnits
                - adjustedOutUnits + otherInUnits - otherOutUnits == closingUnits;
    }
}
