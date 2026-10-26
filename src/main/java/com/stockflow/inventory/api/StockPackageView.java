package com.stockflow.inventory.api;

import java.util.Locale;
import java.util.UUID;

/**
 * One carton (stock package) labelled on a goods-receipt line, as the receipt shows it (SCRUM-473).
 *
 * @param uomConversionId the packing unit it was made in; {@code nominalQty} is that unit's factor when it was made
 * @param quantity        what is in it now: what was counted into it while it is RECEIVING (null until counted);
 *                        once in stock, what is left in it - the sum of its stock rows, since a carton holds no
 *                        quantity of its own
 * @param receivedQty     what was counted into it ({@code received_qty}, null until counted); it does not change once
 *                        the carton is in stock, so a receipt's loose part is its line less this, whatever left the
 *                        carton since
 * @param status          RECEIVING, IN_STOCK, CONSUMED or LOST ({@code inventory.stock_package.status})
 * @param unopened        still closed as it was counted: then it holds exactly what was counted into it
 */
public record StockPackageView(
        UUID id,
        String lpn,
        UUID receiptLineId,
        String sku,
        UUID uomConversionId,
        String unitOfMeasure,
        int nominalQty,
        Integer quantity,
        Integer receivedQty,
        String locationCode,
        String status,
        boolean unopened
) {

    /**
     * The form an LPN is stored and matched in: trimmed and upper-cased, as it is printed - a scanner or a person
     * typing it may send it in either case. Blank is absent (null). Every command naming a carton by LPN goes
     * through this, and so does anything comparing a client's LPN with a stored one.
     */
    public static String normalLpn(String lpn) {
        return lpn == null || lpn.isBlank() ? null : lpn.strip().toUpperCase(Locale.ROOT);
    }
}
