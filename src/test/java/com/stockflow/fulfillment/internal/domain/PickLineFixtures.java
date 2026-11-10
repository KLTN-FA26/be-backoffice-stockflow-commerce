package com.stockflow.fulfillment.internal.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * Pick lines as the domain tests need them, through the fullest rehydrating constructor: one carton per line, named by
 * its LPN (team plan 4C-4). A line with no LPN is built only where the test is about history or an impaired share.
 */
final class PickLineFixtures {

    static final Instant NOW = Instant.parse("2026-11-13T03:00:00Z");
    static final UUID PICKER = UUID.randomUUID();
    static final String LPN = "LPN-20261113-000001";
    static final String OTHER_LPN = "LPN-20261113-000002";

    private PickLineFixtures() { }

    static PickList.Hold hold(UUID id, String sku, String location, UUID warehouse, int quantity, String lpn) {
        return new PickList.Hold(id, sku, location, warehouse, null, quantity, true, lpn, true);
    }

    /** An OPEN line: one whole carton, nothing picked. */
    static PickLine open(String sku, String location, int quantity, String lpn) {
        return line(sku, location, quantity, 0, PickLineStatus.OPEN, 0, null, lpn, 0);
    }

    /** A PICKED line: the carton was taken whole. */
    static PickLine picked(String sku, String location, int quantity, String lpn) {
        return line(sku, location, quantity, quantity, PickLineStatus.PICKED, 0, null, lpn, 0);
    }

    /** A PICKED line of which {@code packingDamaged} units were found damaged at the bench (R8). */
    static PickLine pickedPartlyDamaged(String sku, String location, int quantity, String lpn, int packingDamaged) {
        return line(sku, location, quantity, quantity, PickLineStatus.PICKED, 0, null, lpn, packingDamaged);
    }

    /** A line reported short of its whole carton, for {@code reason}. */
    static PickLine shortLine(String sku, String location, int quantity, String lpn, ShortPickReason reason) {
        return line(sku, location, quantity, 0, PickLineStatus.SHORT, quantity, reason, lpn, 0);
    }

    static PickLine line(String sku, String location, int quantity, int picked, PickLineStatus status, int shortQty,
                         ShortPickReason reason, String lpn, int packingDamaged) {
        boolean pickedOrBack = status == PickLineStatus.PICKED || status == PickLineStatus.PUT_BACK;
        return new PickLine(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), sku, location, "L1", 1, quantity,
                picked, status, pickedOrBack ? PICKER : null, pickedOrBack ? NOW : null, pickedOrBack ? NOW : null,
                pickedOrBack ? location : null, pickedOrBack ? lpn : null, 0, null, null, null, shortQty, reason, lpn,
                lpn != null, 0, null, packingDamaged);
    }
}
