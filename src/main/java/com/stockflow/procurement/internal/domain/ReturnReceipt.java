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
 * <p>What came back inside is counted too (WF-05 TH-4), the warehouse invariant left as it is - a carton is in stock
 * only whole: each carton keeps the units found in it ({@link Carton#countedQuantity}), and units that came back with
 * no carton label are {@link LooseLine}s, each with a photo. Neither an opened carton - fewer units than its label,
 * down to none - nor loose units are put into stock (WF-05 Q-18; Luật Kế toán Điều 13 khoản 1: the record states what
 * really came back): the receipt keeps the real count, its result for them is "destroyed", and they are counted as
 * received for the return request. A sealed carton goes into quarantine whole.</p>
 *
 * <p>A receipt holding an opened carton or loose units is confirmed by a warehouse manager who counted nothing on it
 * (WF-05 Q-18 add-on, {@link #confirm(UUID, Instant, boolean)}): a figure one person typed is the ground of a refund
 * and of goods destroyed.</p>
 *
 * <p>What the aggregate decides: exactly one source, the order of the steps, a carton once per receipt, the count
 * inside a carton never above its label, loose units on a return request's receipt only, and that a confirmed or
 * cancelled receipt does not change. What it cannot see, and the service checks - the database again
 * ({@code check_return_receipt}, {@code check_return_receipt_carton}): that the source is coming back now, that the
 * receipt is at its warehouse, that each carton left on it.</p>
 */
public final class ReturnReceipt extends AggregateRoot {

    /**
     * One carton scanned on the receipt, as it was shipped. {@code condition} is what the warehouse saw of its outside
     * (dented, wet, opened - 03 edge cases), null when nothing; the carton is received whole whatever it says.
     * {@code countedQuantity} is what was found inside it (WF-05 TH-4): its {@code quantity} when it came back sealed,
     * fewer - down to none - when it was opened ({@code ck_goods_receipt_return_cartons_counted}). {@code countedBy} is
     * who recorded that count - the scan, or the last recount (WF-05 Q-18: the confirmer is someone else).
     */
    public record Carton(UUID id, UUID stockPackageId, String lpn, String sku, String lotNumber, int quantity,
                         String condition, UUID scannedBy, Instant scannedAt, int countedQuantity, UUID countedBy) {

        public Carton {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(stockPackageId, "stockPackageId");
            Objects.requireNonNull(lpn, "lpn");
            Objects.requireNonNull(sku, "sku");
            Objects.requireNonNull(scannedBy, "scannedBy");
            if (quantity <= 0) {
                throw new IllegalArgumentException("A carton holds something");
            }
            if (countedQuantity < 0 || countedQuantity > quantity) {
                throw new BusinessException(ErrorCode.VALIDATION_FAILED, ("Carton %s holds %d unit(s) by its label: "
                        + "count between 0 and %d inside it").formatted(lpn, quantity, quantity), List.of(),
                        Map.of("lpn", lpn));
            }
            condition = condition == null || condition.isBlank() ? null : condition.strip();
            countedBy = countedBy == null ? scannedBy : countedBy;
        }

        /** Counted by who scanned it. */
        public Carton(UUID id, UUID stockPackageId, String lpn, String sku, String lotNumber, int quantity,
                      String condition, UUID scannedBy, Instant scannedAt, int countedQuantity) {
            this(id, stockPackageId, lpn, sku, lotNumber, quantity, condition, scannedBy, scannedAt, countedQuantity,
                    scannedBy);
        }

        /** Sealed, as shipped: every unit of its label inside. */
        public Carton(UUID id, UUID stockPackageId, String lpn, String sku, String lotNumber, int quantity,
                      String condition, UUID scannedBy, Instant scannedAt) {
            this(id, stockPackageId, lpn, sku, lotNumber, quantity, condition, scannedBy, scannedAt, quantity);
        }

        /** Fewer units inside than its label: it was opened, or came back empty. */
        public boolean opened() {
            return countedQuantity < quantity;
        }

        /**
         * Whether it goes back into stock (QUARANTINE) on confirmation (WF-05 Q-18): only a sealed one. An opened or
         * empty carton stays consumed - its count is recorded here, its goods destroyed - so no stock is ever made from
         * its label ({@code ck_goods_receipt_return_cartons_stock_entered}).
         */
        public boolean stockEntered() {
            return !opened();
        }

        Carton recorded(String newCondition, int newCount, UUID newCounter) {
            return new Carton(id, stockPackageId, lpn, sku, lotNumber, quantity, newCondition, scannedBy, scannedAt,
                    newCount, newCount == countedQuantity ? countedBy : newCounter);
        }
    }

    /**
     * Units that came back with no carton label (WF-05 TH-4): their SKU, their lot when it can be read, how many, and a
     * photo of them - one of the receipt's own ({@code photoId}, WF-05 Q-18; null only on lines recorded before). No
     * stock entry - they are destroyed - but received for the return request. {@code recordedBy} is whose count it is
     * now.
     */
    public record LooseLine(UUID id, String sku, String lotNumber, int quantity, UUID recordedBy, UUID photoId) {

        /** As recorded before loose units needed a photo. */
        public LooseLine(UUID id, String sku, String lotNumber, int quantity, UUID recordedBy) {
            this(id, sku, lotNumber, quantity, recordedBy, null);
        }

        public LooseLine {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(sku, "sku");
            Objects.requireNonNull(recordedBy, "recordedBy");
            if (quantity <= 0) {
                throw new BusinessException(ErrorCode.VALIDATION_FAILED, "Record at least one loose unit");
            }
            lotNumber = lotNumber == null || lotNumber.isBlank() ? null : lotNumber.strip();
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
    private final List<LooseLine> looseLines = new ArrayList<>();
    /** Of a confirmed receipt, whether each carton went into stock, as stored ({@link #restoreStocked}). */
    private final Map<UUID, Boolean> stocked = new java.util.HashMap<>();
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
        return scan(carton, false);
    }

    /**
     * As {@link #scan(Carton)}, and when {@code counted} the units the carton holds this time replace the count noted
     * before (WF-05 TH-4: a carton is scanned again once it was opened and counted).
     */
    public Carton scan(Carton carton, boolean counted) {
        requireDraft("scan a carton on");
        for (int i = 0; i < cartons.size(); i++) {
            Carton held = cartons.get(i);
            if (held.stockPackageId().equals(carton.stockPackageId())) {
                String condition = carton.condition() != null ? carton.condition() : held.condition();
                int count = counted ? carton.countedQuantity() : held.countedQuantity();
                if (!java.util.Objects.equals(condition, held.condition()) || count != held.countedQuantity()) {
                    // A new count is its recorder's (carton.countedBy()): the confirmer must be someone else.
                    held = held.recorded(condition, count, carton.countedBy());
                    cartons.set(i, held);
                }
                return held;
            }
        }
        cartons.add(carton);
        return carton;
    }

    /**
     * Units that came back with no carton label (WF-05 TH-4), on a receipt against a return request - a shipment the
     * carrier brought back comes back as it left, in its cartons. Recorded again for the same SKU and lot, the count -
     * and with it the photo and whose count it is - replaces the one before. The caller has checked the SKU is one of
     * the order's, the photo is one of this receipt's, and the return approved that many still to come back (WF-05
     * Q-18; {@code check_return_receipt_loose_line} again).
     *
     * @throws BusinessException {@code INVALID_RECEIPT_TRANSITION} for a receipt not a draft, or one against a shipment
     */
    public LooseLine recordLoose(LooseLine line) {
        requireLooseRecordable();
        for (int i = 0; i < looseLines.size(); i++) {
            LooseLine held = looseLines.get(i);
            if (held.sku().equals(line.sku()) && java.util.Objects.equals(held.lotNumber(), line.lotNumber())) {
                LooseLine recounted = new LooseLine(held.id(), held.sku(), held.lotNumber(), line.quantity(),
                        line.recordedBy(), line.photoId());
                looseLines.set(i, recounted);
                return recounted;
            }
        }
        looseLines.add(line);
        return line;
    }

    /** Loose units recorded by mistake come off the draft. */
    public void removeLoose(UUID lineId) {
        requireDraft("remove loose units from");
        if (!looseLines.removeIf(l -> l.id().equals(lineId))) {
            throw new BusinessException(ErrorCode.NOT_FOUND,
                    "Return receipt %s has no loose line %s".formatted(number, lineId));
        }
    }

    /**
     * Whether each carton of a confirmed receipt went into stock, as stored: a receipt confirmed before WF-05 Q-18 put
     * its opened cartons into stock too, so for such a receipt this is not read off the count.
     */
    public ReturnReceipt restoreStocked(Map<UUID, Boolean> stored) {
        stocked.clear();
        stocked.putAll(stored);
        return this;
    }

    /** Whether {@code carton} goes - or, confirmed, went - back into stock ({@link Carton#stockEntered}). */
    public boolean stockEntered(Carton carton) {
        return stocked.getOrDefault(carton.id(), carton.stockEntered());
    }

    /**
     * Refuses loose units on a receipt that takes none: not a draft, or against a shipment the carrier brought back.
     * Asked by the service before it checks the photo and the cap, so the refusal says what is wrong with the receipt.
     */
    public void requireLooseRecordable() {
        requireDraft("record loose units on");
        if (returnRequestId == null) {
            throw new BusinessException(ErrorCode.INVALID_RECEIPT_TRANSITION, ("Return receipt %s is against a shipment "
                    + "the carrier brought back: its goods come back in their cartons").formatted(number));
        }
    }

    /** Rehydration of the loose units, as stored. */
    public ReturnReceipt restoreLoose(List<LooseLine> stored) {
        looseLines.clear();
        looseLines.addAll(stored);
        return this;
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
     * the same transaction. A receipt of loose units only (WF-05 TH-4) closes too: nothing goes into stock.
     */
    public void confirm(UUID userId, Instant now) {
        confirm(userId, now, false);
    }

    /**
     * As {@link #confirm(UUID, Instant)}, by someone who holds the warehouse manager's approval of return receipts
     * ({@code managerApproval}) or not. A receipt with an opened carton or loose units (WF-05 Q-18 add-on) - figures
     * that destroy goods and ground a refund - is confirmed only with that approval, and only by someone who counted
     * nothing on it: not who scanned or counted a carton, nor who recorded loose units ({@code
     * check_return_receipt_closed} again).
     *
     * @throws BusinessException {@code INVALID_RECEIPT_TRANSITION}; {@code RETURN_RECEIPT_MANAGER_CONFIRMATION_REQUIRED};
     *         {@code RETURN_RECEIPT_SELF_CONFIRMATION}
     */
    public void confirm(UUID userId, Instant now, boolean managerApproval) {
        requireDraft("confirm");
        if (cartons.isEmpty() && looseLines.isEmpty()) {
            throw new BusinessException(ErrorCode.INVALID_RECEIPT_TRANSITION,
                    "Return receipt %s has no carton to receive; scan the cartons that came back".formatted(number));
        }
        if (needsManagerConfirmation()) {
            if (!managerApproval) {
                throw new BusinessException(ErrorCode.RETURN_RECEIPT_MANAGER_CONFIRMATION_REQUIRED, ("Return receipt %s "
                        + "holds opened cartons or loose units: a warehouse manager confirms it").formatted(number));
            }
            if (countedBy(userId)) {
                throw new BusinessException(ErrorCode.RETURN_RECEIPT_SELF_CONFIRMATION, ("Return receipt %s was counted "
                        + "in part by the same person: someone else confirms it").formatted(number));
            }
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

    /** Whether it holds an opened carton or loose units: a manager who counted nothing confirms it (WF-05 Q-18). */
    public boolean needsManagerConfirmation() {
        return !looseLines.isEmpty() || cartons.stream().anyMatch(Carton::opened);
    }

    /** Whether {@code userId} scanned or counted a carton, or recorded loose units, on it. */
    public boolean countedBy(UUID userId) {
        return cartons.stream().anyMatch(c -> c.scannedBy().equals(userId) || c.countedBy().equals(userId))
                || looseLines.stream().anyMatch(l -> l.recordedBy().equals(userId));
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
    /** Units that came back with no carton label (WF-05 TH-4). */
    public List<LooseLine> looseLines() { return Collections.unmodifiableList(looseLines); }
    public GoodsReceiptStatus status() { return status; }
    public Instant confirmedAt() { return confirmedAt; }
    public UUID confirmedBy() { return confirmedBy; }
    public Instant closedAt() { return closedAt; }
    public long version() { return version; }
}
