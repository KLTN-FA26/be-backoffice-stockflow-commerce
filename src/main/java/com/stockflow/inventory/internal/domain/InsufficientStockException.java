package com.stockflow.inventory.internal.domain;

import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;

/**
 * Thrown when a reservation asks for more than is available to promise.
 *
 * <p>Extends {@code BusinessException} so {@code GlobalExceptionHandler} turns it into
 * {@code 409 INSUFFICIENT_STOCK} without inventory writing any web code. It carries the numbers
 * as fields, not only inside the message, so the handler can put them in the response body and a
 * caller can react programmatically ("only 3 left") rather than parsing English.</p>
 */
public class InsufficientStockException extends BusinessException {

    @java.io.Serial
    private static final long serialVersionUID = 1L;

    private final String sku;
    private final int requested;
    private final int available;

    public InsufficientStockException(String sku, int requested, int available) {
        super(ErrorCode.INSUFFICIENT_STOCK,
                "Insufficient stock for SKU %s: requested %d, available %d"
                        .formatted(sku, requested, available));
        this.sku = sku;
        this.requested = requested;
        this.available = available;
    }

    public String sku() { return sku; }
    public int requested() { return requested; }
    public int available() { return available; }
}
