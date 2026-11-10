package com.stockflow.inventory.internal.domain;

import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * One physical carton with its own LPN (GitHub #125, SCRUM-473): which item, made in which packing unit,
 * where it is, and whether it is still unopened.
 *
 * <p><b>Whole cartons only (team plan 4C-2; kltn-docs 20 §4.6, §5.5).</b> A carton holds the SKU's standard count -
 * its packing unit's factor - from the day it is received (03 BR-13), and leaves stock whole: it is never opened,
 * nothing is taken out of part of it. So there is no "open" any more; taking its contents ends it
 * ({@link #consume}), and a carton that comes back whole - put back after its order was cancelled, or returned by a
 * customer - is in stock again, sealed ({@link #restock}).</p>
 *
 * <p><b>It holds no quantity of its own.</b> What is left in it is the stock rows that point at it
 * ({@link StockItem#stockPackageId()}), the sum of their {@code on_hand}; two copies of one number would
 * drift. {@code nominalQty} is the packing unit's factor when it was made and {@code receivedQty} what was
 * counted into it at receipt, fixed once it is in stock.</p>
 *
 * <p>Every rule below is stated a second time by V20261020000100 (checks and the transition trigger), so a
 * path that bypassed this class is still refused - at commit, as a 500, where this class refuses first
 * with an error code.</p>
 */
public final class StockPackage {

    private static final Pattern LPN = Pattern.compile("[A-Z0-9][A-Z0-9-]{3,29}");

    private final StockPackageId id;
    private final String lpn;
    private final UUID inventoryItemId;
    private final UUID uomConversionId;
    private final int nominalQty;
    private final UUID goodsReceiptLineId;
    private final long version;
    private Integer receivedQty;
    private LocationId location;
    private StockPackageStatus status;
    private boolean unopened;
    private Instant closedAt;

    public StockPackage(StockPackageId id, String lpn, UUID inventoryItemId, UUID uomConversionId, int nominalQty,
                        Integer receivedQty, LocationId location, StockPackageStatus status, boolean unopened,
                        UUID goodsReceiptLineId, Instant closedAt, long version) {
        this.id = Objects.requireNonNull(id, "id");
        this.lpn = Objects.requireNonNull(lpn, "lpn");
        this.inventoryItemId = Objects.requireNonNull(inventoryItemId, "inventoryItemId");
        this.uomConversionId = Objects.requireNonNull(uomConversionId, "uomConversionId");
        this.nominalQty = nominalQty;
        this.receivedQty = receivedQty;
        this.location = Objects.requireNonNull(location, "location");
        this.status = Objects.requireNonNull(status, "status");
        this.unopened = unopened;
        this.goodsReceiptLineId = goodsReceiptLineId;
        this.closedAt = closedAt;
        this.version = version;
        checkInvariants();
    }

    /** A carton labelled on a receipt line, still to be counted ({@code tg_stock_package_created}). */
    public static StockPackage receiving(StockPackageId id, String lpn, UUID inventoryItemId, UUID uomConversionId,
                                         int factor, LocationId receivingArea, UUID goodsReceiptLineId) {
        return new StockPackage(id, lpn, inventoryItemId, uomConversionId, factor, null, receivingArea,
                StockPackageStatus.RECEIVING, true, Objects.requireNonNull(goodsReceiptLineId, "goodsReceiptLineId"),
                null, 0L);
    }

    // ------------------------------------------------------------------ behaviour

    /**
     * Counted (again) while still being received: it holds the standard count, {@link #nominalQty}, which the system
     * assigns rather than anyone typing a number (kltn-docs 03 BR-13: a carton of another count is not listed at all).
     * Fixed once in stock ({@code tg_stock_package_transition}).
     */
    public void count() {
        requireStatus(StockPackageStatus.RECEIVING, "be recounted");
        receivedQty = nominalQty;
    }

    /**
     * RECEIVING → IN_STOCK: its receipt was posted. It must have been counted at the standard count: a draft counted
     * before whole cartons were the rule may still carry another figure, and is recounted first.
     */
    public void putInStock() {
        requireStatus(StockPackageStatus.RECEIVING, "go into stock");
        if (receivedQty == null) {
            throw transition("%s has not been counted".formatted(lpn));
        }
        requireStandardCount();
        status = StockPackageStatus.IN_STOCK;
    }

    /**
     * Refuses a carton counted at another figure than its standard count (kltn-docs 03 BR-13), naming it: asked
     * first by a receipt, so a draft counted under the old rules is reported as that carton, not as a line whose
     * cartons fall short. A carton not yet counted passes; {@link #putInStock} refuses it.
     */
    public void requireStandardCount() {
        if (receivedQty != null && receivedQty != nominalQty) {
            throw new BusinessException(ErrorCode.RECEIPT_PACKAGE_NOT_STANDARD,
                    "%s was counted at %d; a carton holds %d - count the line again".formatted(lpn, receivedQty,
                            nominalQty));
        }
    }

    /** RECEIVING → VOID: its receipt was cancelled before posting. */
    public void cancel(Instant now) {
        requireStatus(StockPackageStatus.RECEIVING, "be voided");
        status = StockPackageStatus.VOID;
        closedAt = Objects.requireNonNull(now, "now");
    }

    /** IN_STOCK → CONSUMED: it left stock whole - picked for an order, or written off whole. */
    public void consume(Instant now) {
        requireStatus(StockPackageStatus.IN_STOCK, "be emptied");
        unopened = false;
        status = StockPackageStatus.CONSUMED;
        closedAt = Objects.requireNonNull(now, "now");
    }

    /**
     * CONSUMED → IN_STOCK at {@code location}, sealed: the carton came back whole (kltn-docs 20 §4.6 steps 5-6) - put
     * back after its order was cancelled, or received again on a return. It keeps its LPN; its rows must hold its
     * count again in the same transaction (V20261020000200 checks at commit). Only a carton that was in stock was
     * ever consumed, so it was counted in at its standard count.
     */
    public void restock(LocationId location) {
        requireStatus(StockPackageStatus.CONSUMED, "come back into stock");
        this.location = Objects.requireNonNull(location, "location");
        status = StockPackageStatus.IN_STOCK;
        unopened = true;
        closedAt = null;
    }

    /** IN_STOCK → LOST: a count did not find it. Nobody knows what is in it any more, so it is not unopened. */
    public void lose(Instant now) {
        requireStatus(StockPackageStatus.IN_STOCK, "be marked lost");
        unopened = false;
        status = StockPackageStatus.LOST;
        closedAt = Objects.requireNonNull(now, "now");
    }

    /**
     * LOST → IN_STOCK: found again, whole, and sealed (team plan 4C-2: nothing in stock is an opened carton, and only
     * a sealed one is ever allocated). A carton found with less than was counted into it is not found: the count that
     * finds it states it present or missing, never part of it. Its rows must hold its count again in the same
     * transaction ({@code check_stock_package_contents} checks a sealed carton at commit); {@code StockPackageContents}
     * refuses a write-up of anything less first.
     */
    public void found() {
        requireStatus(StockPackageStatus.LOST, "be found");
        status = StockPackageStatus.IN_STOCK;
        unopened = true;
        closedAt = null;
    }

    /** Moved whole; its stock rows move with it in the same transaction ({@code check_stock_package_contents}). */
    public void moveTo(LocationId destination) {
        if (status.isClosed()) {
            throw transition("%s is %s; a closed carton is not moved".formatted(lpn, status));
        }
        location = Objects.requireNonNull(destination, "destination");
    }

    // ------------------------------------------------------------------ invariants

    private void checkInvariants() {
        if (!LPN.matcher(lpn).matches()) {
            throw new IllegalStateException("LPN %s is not 4-30 upper-case letters, digits or '-'".formatted(lpn));
        }
        if (nominalQty <= 0) {
            throw new IllegalStateException("Stock package %s: nominal quantity %d".formatted(lpn, nominalQty));
        }
        if (receivedQty != null && receivedQty < 0) {
            throw new IllegalStateException("Stock package %s: received %d".formatted(lpn, receivedQty));
        }
        if (receivedQty == null && status != StockPackageStatus.RECEIVING && status != StockPackageStatus.VOID) {
            throw new IllegalStateException("Stock package %s is %s but was never counted".formatted(lpn, status));
        }
        if (status.isClosed() != (closedAt != null)) {
            throw new IllegalStateException("Stock package %s is %s with closed_at %s".formatted(lpn, status, closedAt));
        }
        if (unopened && status != StockPackageStatus.RECEIVING && status != StockPackageStatus.IN_STOCK
                && status != StockPackageStatus.VOID) {
            throw new IllegalStateException("Stock package %s is %s and cannot be unopened".formatted(lpn, status));
        }
    }

    private void requireStatus(StockPackageStatus expected, String action) {
        if (status != expected) {
            throw transition("%s is %s and cannot %s".formatted(lpn, status, action));
        }
    }

    private static BusinessException transition(String message) {
        return new BusinessException(ErrorCode.INVALID_STOCK_PACKAGE_TRANSITION, message);
    }

    // ------------------------------------------------------------------ queries

    public StockPackageId id() { return id; }
    public String lpn() { return lpn; }
    public UUID inventoryItemId() { return inventoryItemId; }
    public UUID uomConversionId() { return uomConversionId; }
    public int nominalQty() { return nominalQty; }
    public Integer receivedQty() { return receivedQty; }
    public LocationId location() { return location; }
    public StockPackageStatus status() { return status; }
    public boolean unopened() { return unopened; }
    public UUID goodsReceiptLineId() { return goodsReceiptLineId; }
    public Instant closedAt() { return closedAt; }
    public long version() { return version; }
}
