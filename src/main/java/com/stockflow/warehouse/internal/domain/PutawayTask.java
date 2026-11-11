package com.stockflow.warehouse.internal.domain;

import com.stockflow.common.domain.AggregateRoot;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.warehouse.api.NewPutawayTask;
import com.stockflow.warehouse.api.PutawaySource;

import java.util.Objects;
import java.util.UUID;

/**
 * A carton still to be put in a bin (kltn-docs 05 BR-08): one of a goods receipt line, or one a warehouse manager
 * released from quarantine ({@link PutawaySource}, 05 BR-01). Tasks of loose stock of a receipt line are history.
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
    private final PutawaySource source;
    private UUID targetLocationId;
    private PutawayStatus status;

    /** Rehydration of a receipt line's task. */
    public PutawayTask(UUID id, UUID goodsReceiptId, UUID goodsReceiptLineId, String sku, int quantity,
                       UUID stockPackageId, UUID targetLocationId, PutawayStatus status) {
        this(id, goodsReceiptId, goodsReceiptLineId, sku, quantity, stockPackageId, targetLocationId, status,
                PutawaySource.RECEIPT);
    }

    /** Rehydration from storage. New tasks go through {@link #open}. */
    public PutawayTask(UUID id, UUID goodsReceiptId, UUID goodsReceiptLineId, String sku, int quantity,
                       UUID stockPackageId, UUID targetLocationId, PutawayStatus status, PutawaySource source) {
        this.id = Objects.requireNonNull(id, "id");
        this.goodsReceiptId = goodsReceiptId;
        this.goodsReceiptLineId = goodsReceiptLineId;
        this.sku = sku;
        this.quantity = quantity;
        this.stockPackageId = stockPackageId;
        this.targetLocationId = targetLocationId;
        this.status = Objects.requireNonNull(status, "status");
        this.source = Objects.requireNonNull(source, "source");
    }

    public static PutawayTask open(UUID id, NewPutawayTask task) {
        if (task.quantity() < 1) {
            throw DomainChecks.invalid("A putaway task needs at least one unit, got " + task.quantity());
        }
        // Kltn-docs 05 BR-08 (team plan 4C-2): a task is one carton, put away whole; a receipt puts every unit into a
        // carton, so there is no loose remainder to put away. Stated again by ck_putaway_task_receipt_carton.
        if (task.stockPackageId() == null) {
            throw DomainChecks.invalid("A putaway task names the carton it puts away");
        }
        // ck_putaway_task_source says it again: a receipt's task names its line, a quarantine release none (05 BR-01).
        if (task.source() == PutawaySource.RECEIPT) {
            Objects.requireNonNull(task.goodsReceiptId(), "goodsReceiptId");
            Objects.requireNonNull(task.goodsReceiptLineId(), "goodsReceiptLineId");
        } else if (task.goodsReceiptId() != null || task.goodsReceiptLineId() != null) {
            throw DomainChecks.invalid("A carton released from quarantine is put away for no receipt line");
        }
        return new PutawayTask(id, task.goodsReceiptId(), task.goodsReceiptLineId(),
                DomainChecks.requiredText("sku", task.sku(), 64), task.quantity(), task.stockPackageId(), null,
                PutawayStatus.PENDING, task.source());
    }

    /**
     * @param units      put into {@code locationId} now
     * @param leftBefore what the task had left before these units
     */
    public void recordPutaway(int units, int leftBefore, UUID locationId) {
        if (status == PutawayStatus.DONE) {
            throw new BusinessException(ErrorCode.PUTAWAY_TASK_DONE, "Putaway task " + id + " is already done");
        }
        if (status == PutawayStatus.CANCELLED) {
            // Recording on it would reopen it as IN_PROGRESS or DONE, whoever the caller is.
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "Putaway task " + id + " was cancelled");
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
    public PutawaySource source() { return source; }
}
