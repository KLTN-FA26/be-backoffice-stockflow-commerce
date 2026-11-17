package com.stockflow.procurement.internal.domain;

import com.stockflow.common.domain.AggregateRoot;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * A return receipt ("phiếu nhập Hàng hoàn"; team plan 4D-2; kltn-docs 03 §1, §4.2 steps 9-13, §5, BR-03, BR-14; 17
 * BR-18; 09 BR-06): goods that came back - a shipment the carrier brought back (RTO), or the goods of an approved
 * return request (RMA) - taken into the warehouse they left from, carton by carton, straight into quarantine.
 *
 * <pre>
 *   DRAFT ──confirm──▶ CLOSED        (cartons back in stock, QUARANTINE; no RECEIVING, no putaway task)
 *     └────cancel───▶ CANCELLED     (nothing was received; the cartons stay as they were)
 * </pre>
 *
 * <p>A goods receipt of type {@code RETURN}: it shares the table and the numbering with the other receipts but not
 * their shape - it has no lines and counts nothing; it recognises cartons by LPN, each keeping the LPN, SKU, count and
 * lot it left with (BR-03). It is closed the moment it is confirmed: what happens to the goods next is a warehouse
 * manager's decision on each carton in quarantine (kltn-docs 20 §4.8), not the receipt's.</p>
 *
 * <p>What the aggregate decides: exactly one source, the order of the steps, a carton once per receipt, and that a
 * confirmed or cancelled receipt does not change. What it cannot see, and the service checks - the database again
 * ({@code check_return_receipt}, {@code check_return_receipt_carton}): that the source is coming back now, that the
 * receipt is at its warehouse, that each carton left on it.</p>
 */
public final class ReturnReceipt extends AggregateRoot {

    /**
     * One carton scanned on the receipt, as it was shipped. {@code condition} is what the warehouse saw of its outside
     * (dented, wet, opened - 03 edge cases), null when nothing; the carton is received whole whatever it says.
     */
    public record Carton(UUID id, UUID stockPackageId, String lpn, String sku, String lotNumber, int quantity,
                         String condition, UUID scannedBy, Instant scannedAt) {

        public Carton {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(stockPackageId, "stockPackageId");
            Objects.requireNonNull(lpn, "lpn");
            Objects.requireNonNull(sku, "sku");
            Objects.requireNonNull(scannedBy, "scannedBy");
            if (quantity <= 0) {
                throw new IllegalArgumentException("A carton holds something");
            }
            condition = condition == null || condition.isBlank() ? null : condition.strip();
        }

        Carton withCondition(String newCondition) {
            return new Carton(id, stockPackageId, lpn, sku, lotNumber, quantity, newCondition, scannedBy, scannedAt);
        }
    }

    private final UUID id;
    private final String number;
    private final UUID warehouseId;
    private final UUID orderId;
    private final UUID shipmentId;
    private final UUID returnRequestId;
    private final UUID quarantineLocationId;
    private final Instant receivedAt;
    private final UUID receivedBy;
    private final String note;
    private final List<Carton> cartons;
    private final long version;

    private GoodsReceiptStatus status;
    private Instant confirmedAt;
    private UUID confirmedBy;
    private Instant closedAt;

    public ReturnReceipt(UUID id, String number, UUID warehouseId, UUID orderId, UUID shipmentId,
                         UUID returnRequestId, UUID quarantineLocationId, Instant receivedAt, UUID receivedBy,
                         String note, List<Carton> cartons, GoodsReceiptStatus status, Instant confirmedAt,
                         UUID confirmedBy, Instant closedAt, long version) {
        this.id = Objects.requireNonNull(id, "id");
        this.number = Objects.requireNonNull(number, "number");
        this.warehouseId = Objects.requireNonNull(warehouseId, "warehouseId");
        this.orderId = Objects.requireNonNull(orderId, "orderId");
        if ((shipmentId == null) == (returnRequestId == null)) {
            throw new IllegalArgumentException("A return receipt is against one shipment or one return request");
        }
        this.shipmentId = shipmentId;
        this.returnRequestId = returnRequestId;
        this.quarantineLocationId = Objects.requireNonNull(quarantineLocationId, "quarantineLocationId");
        this.receivedAt = Objects.requireNonNull(receivedAt, "receivedAt");
        this.receivedBy = Objects.requireNonNull(receivedBy, "receivedBy");
        this.note = note == null || note.isBlank() ? null : note.strip();
        this.cartons = new ArrayList<>(cartons == null ? List.of() : cartons);
        this.status = Objects.requireNonNull(status, "status");
        if (status != GoodsReceiptStatus.DRAFT && status != GoodsReceiptStatus.CLOSED
                && status != GoodsReceiptStatus.CANCELLED) {
            throw new IllegalArgumentException("A return receipt is DRAFT, CLOSED or CANCELLED, not " + status);
        }
        this.confirmedAt = confirmedAt;
        this.confirmedBy = confirmedBy;
        this.closedAt = closedAt;
        this.version = version;
    }

    /** A draft for a shipment the carrier brought back (RTO; 09 BR-06). */
    public static ReturnReceipt forShipment(UUID id, String number, UUID warehouseId, UUID orderId, UUID shipmentId,
                                            UUID quarantineLocationId, UUID receivedBy, String note, Instant now) {
        return new ReturnReceipt(id, number, warehouseId, orderId, Objects.requireNonNull(shipmentId, "shipmentId"),
                null, quarantineLocationId, now, receivedBy, note, List.of(), GoodsReceiptStatus.DRAFT, null, null,
                null, 0L);
    }

    /** A draft for the goods of an approved return request (RMA; 17 §4.10). */
    public static ReturnReceipt forReturnRequest(UUID id, String number, UUID warehouseId, UUID orderId,
                                                 UUID returnRequestId, UUID quarantineLocationId, UUID receivedBy,
                                                 String note, Instant now) {
        return new ReturnReceipt(id, number, warehouseId, orderId, null,
                Objects.requireNonNull(returnRequestId, "returnRequestId"), quarantineLocationId, now, receivedBy,
                note, List.of(), GoodsReceiptStatus.DRAFT, null, null, null, 0L);
    }

    // ------------------------------------------------------------------ scanning

    /**
     * Refuses a scan on a receipt that is no longer a draft. Asked by the service before it checks the carton against
     * the source: a closed receipt is refused for being closed, not for what its carton has become since.
     */
    public void requireScannable() {
        requireDraft("scan a carton on");
    }

    /**
     * A carton scanned (03 §4.2 step 10). Scanned again it is the same carton: nothing is added, and a condition sent
     * this time replaces the one noted before (null keeps it). The caller has checked it left on this receipt's
     * shipment or order.
     *
     * @return the carton as the receipt now holds it
     */
    public Carton scan(Carton carton) {
        requireDraft("scan a carton on");
        for (int i = 0; i < cartons.size(); i++) {
            Carton held = cartons.get(i);
            if (held.stockPackageId().equals(carton.stockPackageId())) {
                if (carton.condition() != null && !carton.condition().equals(held.condition())) {
                    held = held.withCondition(carton.condition());
                    cartons.set(i, held);
                }
                return held;
            }
        }
        cartons.add(carton);
        return carton;
    }

    /** A carton scanned by mistake comes off the draft. */
    public void remove(String lpn) {
        requireDraft("remove a carton from");
        if (!cartons.removeIf(c -> c.lpn().equals(lpn))) {
            throw new BusinessException(ErrorCode.STOCK_PACKAGE_NOT_FOUND,
                    "Carton %s is not on return receipt %s".formatted(lpn, number), List.of(), Map.of("lpn", lpn));
        }
    }

    // ------------------------------------------------------------------ deciding

    /**
     * The cartons that came are received (03 §4.2 step 12): the receipt is confirmed and closed at once - there is no
     * putaway. Fewer cartons than the shipment or return request carried is no reason to wait (03 edge cases: the
     * warehouse manager settles the rest outside the system). The caller brings the cartons back into quarantine in
     * the same transaction.
     */
    public void confirm(UUID userId, Instant now) {
        requireDraft("confirm");
        if (cartons.isEmpty()) {
            throw new BusinessException(ErrorCode.INVALID_RECEIPT_TRANSITION,
                    "Return receipt %s has no carton to receive; scan the cartons that came back".formatted(number));
        }
        this.confirmedBy = Objects.requireNonNull(userId, "userId");
        this.confirmedAt = Objects.requireNonNull(now, "now");
        this.closedAt = now;
        this.status = GoodsReceiptStatus.CLOSED;
    }

    /** Only a draft (03 BR-05): its cartons were never received, so they stay as they were. */
    public void cancel() {
        requireDraft("cancel");
        this.status = GoodsReceiptStatus.CANCELLED;
    }

    private void requireDraft(String verb) {
        if (status != GoodsReceiptStatus.DRAFT) {
            throw new BusinessException(ErrorCode.INVALID_RECEIPT_TRANSITION,
                    "Cannot %s return receipt %s: it is %s, not DRAFT".formatted(verb, number, status));
        }
    }

    // ------------------------------------------------------------------ queries

    public Optional<Carton> carton(String lpn) {
        return cartons.stream().filter(c -> c.lpn().equals(lpn)).findFirst();
    }

    public UUID id() { return id; }
    public String number() { return number; }
    public UUID warehouseId() { return warehouseId; }
    public UUID orderId() { return orderId; }
    /** The shipment the carrier brought back; null when the receipt is against a return request. */
    public UUID shipmentId() { return shipmentId; }
    /** The return request; null when the receipt is against a returned shipment. */
    public UUID returnRequestId() { return returnRequestId; }
    public UUID quarantineLocationId() { return quarantineLocationId; }
    public Instant receivedAt() { return receivedAt; }
    public UUID receivedBy() { return receivedBy; }
    public String note() { return note; }
    public List<Carton> cartons() { return Collections.unmodifiableList(cartons); }
    public GoodsReceiptStatus status() { return status; }
    public Instant confirmedAt() { return confirmedAt; }
    public UUID confirmedBy() { return confirmedBy; }
    public Instant closedAt() { return closedAt; }
    public long version() { return version; }
}
