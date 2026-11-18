package com.stockflow.inventory.api;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Cartons of a return receipt to bring back into quarantine (team plan 4D-2; kltn-docs 03 §4.2 step 12).
 *
 * @param receiptId    the return receipt: every ledger line names it ({@code GOODS_RECEIPT})
 * @param orderId      the customer order the cartons were shipped on, named on each ledger line so the lot trace
 *                     reaches it
 * @param locationCode the receipt's QUARANTINE area
 * @param lpns         the cartons, at least one, each once (normalised as {@link StockPackageView#normalLpns})
 * @param openedLpns   those of them the receipt found opened - fewer units inside than the label (WF-05 TH-4): they
 *                     come back into quarantine whole like the rest, and are marked so they never leave it but by a
 *                     write-off. Empty when every carton came back sealed
 */
public record ReceiveReturnedCartonsCommand(UUID receiptId, UUID orderId, String locationCode, List<String> lpns,
                                            UUID actorId, Instant receivedAt, Set<String> openedLpns) {

    public ReceiveReturnedCartonsCommand {
        Objects.requireNonNull(receiptId, "receiptId");
        Objects.requireNonNull(orderId, "orderId");
        Objects.requireNonNull(locationCode, "locationCode");
        Objects.requireNonNull(receivedAt, "receivedAt");
        lpns = StockPackageView.normalLpns(lpns);
        openedLpns = openedLpns == null || openedLpns.isEmpty() ? Set.of()
                : Set.copyOf(StockPackageView.normalLpns(List.copyOf(openedLpns)));
        if (!lpns.containsAll(openedLpns)) {
            throw new IllegalArgumentException("An opened carton is one of the cartons received");
        }
    }

    /** Every carton sealed. */
    public ReceiveReturnedCartonsCommand(UUID receiptId, UUID orderId, String locationCode, List<String> lpns,
                                         UUID actorId, Instant receivedAt) {
        this(receiptId, orderId, locationCode, lpns, actorId, receivedAt, Set.of());
    }
}
