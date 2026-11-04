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

    /** Counted (again) while still being received; fixed once in stock ({@code tg_stock_package_transition}). */
    public void count(int quantity) {
        requireStatus(StockPackageStatus.RECEIVING, "be recounted");
        if (quantity < 0) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "A carton holds no fewer than 0 units");
        }
        receivedQty = quantity;
    }

    /** RECEIVING → IN_STOCK: its receipt was posted; it must have been counted. */
    public void putInStock() {
        requireStatus(StockPackageStatus.RECEIVING, "go into stock");
        if (receivedQty == null) {
            throw transition("%s has not been counted".formatted(lpn));
        }
        status = StockPackageStatus.IN_STOCK;
    }

    /** RECEIVING → VOID: its receipt was cancelled before posting. */
    public void cancel(Instant now) {
        requireStatus(StockPackageStatus.RECEIVING, "be voided");
        status = StockPackageStatus.VOID;
        closedAt = Objects.requireNonNull(now, "now");
    }

    /** Something was taken out of it, or put into it: it is no longer as it was counted. Once open, open. */
    public void open() {
        requireStatus(StockPackageStatus.IN_STOCK, "be opened");
        unopened = false;
    }

    /** IN_STOCK → CONSUMED: its last unit left it. */
    public void consume(Instant now) {
        requireStatus(StockPackageStatus.IN_STOCK, "be emptied");
        unopened = false;
        status = StockPackageStatus.CONSUMED;
        closedAt = Objects.requireNonNull(now, "now");
    }

    /**
     * Units left this carton's rows, {@code left} remaining in them. The one rule every path that takes stock out
     * of a carton applies (a pick, a package, an issue, a transfer, a write-off): taken from, it is opened - it no
     * longer holds what was counted into it - and emptied, it is CONSUMED, in the same transaction, or the
     * commit-time check of V20261020000200 refuses (an IN_STOCK carton holds something; an unopened one holds
     * exactly its count).
     */
    public void contentsTaken(int left, Instant now) {
        if (left < 0) {
            throw new IllegalStateException("Stock package %s would hold %d".formatted(lpn, left));
        }
        if (left == 0) {
            consume(now);
        } else {
            open();
        }
    }

    /** IN_STOCK → LOST: a count did not find it. Nobody knows what is in it any more, so it is not unopened. */
    public void lose(Instant now) {
        requireStatus(StockPackageStatus.IN_STOCK, "be marked lost");
        unopened = false;
        status = StockPackageStatus.LOST;
        closedAt = Objects.requireNonNull(now, "now");
    }

    /** LOST → IN_STOCK: found again. */
    public void found() {
        requireStatus(StockPackageStatus.LOST, "be found");
        status = StockPackageStatus.IN_STOCK;
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
