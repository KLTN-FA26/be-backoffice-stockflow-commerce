package com.stockflow.warehouse.internal.domain;

import com.stockflow.common.domain.AggregateRoot;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.warehouse.api.NewPutawayTask;

import java.util.Objects;
import java.util.UUID;

/**
 * Something received that is still to be put in a bin: one goods receipt line of loose stock, or one carton.
 *
 * <p>The task does not count what has been put away. Loose stock can go to several bins, and the stock ledger already
 * knows how much; the caller states what was left before a putaway and the task checks that against the units it
 * records. A carton has no parts: it goes into one bin, whole.</p>
 */
public final class PutawayTask extends AggregateRoot {

    private final UUID id;
    private final UUID goodsReceiptId;
    private final UUID goodsReceiptLineId;
    private final String sku;
    private final int quantity;
    private final UUID stockPackageId;
    private UUID targetLocationId;
    private PutawayStatus status;

    /** Rehydration from storage. New tasks go through {@link #open}. */
    public PutawayTask(UUID id, UUID goodsReceiptId, UUID goodsReceiptLineId, String sku, int quantity,
                       UUID stockPackageId, UUID targetLocationId, PutawayStatus status) {
        this.id = Objects.requireNonNull(id, "id");
        this.goodsReceiptId = goodsReceiptId;
        this.goodsReceiptLineId = goodsReceiptLineId;
        this.sku = sku;
        this.quantity = quantity;
        this.stockPackageId = stockPackageId;
        this.targetLocationId = targetLocationId;
        this.status = Objects.requireNonNull(status, "status");
    }

    public static PutawayTask open(UUID id, NewPutawayTask task) {
        if (task.quantity() < 1) {
            throw DomainChecks.invalid("A putaway task needs at least one unit, got " + task.quantity());
        }
        return new PutawayTask(id, Objects.requireNonNull(task.goodsReceiptId(), "goodsReceiptId"),
                Objects.requireNonNull(task.goodsReceiptLineId(), "goodsReceiptLineId"),
                DomainChecks.requiredText("sku", task.sku(), 64), task.quantity(), task.stockPackageId(), null,
                PutawayStatus.PENDING);
    }

    /**
     * @param units      put into {@code locationId} now
     * @param leftBefore what the task had left before these units
     */
    public void recordPutaway(int units, int leftBefore, UUID locationId) {
        if (status == PutawayStatus.DONE) {
            throw new BusinessException(ErrorCode.PUTAWAY_TASK_DONE, "Putaway task " + id + " is already done");
        }
        if (units < 1 || units > leftBefore) {
            throw new BusinessException(ErrorCode.PUTAWAY_QUANTITY_EXCEEDED,
                    "Putaway task %s has %d left, cannot put away %d".formatted(id, leftBefore, units));
        }
        if (stockPackageId != null && units != quantity) {
            throw new BusinessException(ErrorCode.PUTAWAY_QUANTITY_EXCEEDED,
                    "A carton is put away whole: %d units, not %d".formatted(quantity, units));
        }
        this.targetLocationId = Objects.requireNonNull(locationId, "locationId");
        this.status = leftBefore - units == 0 ? PutawayStatus.DONE : PutawayStatus.IN_PROGRESS;
    }

    public UUID id() { return id; }
    public UUID goodsReceiptId() { return goodsReceiptId; }
    public UUID goodsReceiptLineId() { return goodsReceiptLineId; }
    public String sku() { return sku; }
    public int quantity() { return quantity; }
    public UUID stockPackageId() { return stockPackageId; }
    public UUID targetLocationId() { return targetLocationId; }
    public PutawayStatus status() { return status; }
}
