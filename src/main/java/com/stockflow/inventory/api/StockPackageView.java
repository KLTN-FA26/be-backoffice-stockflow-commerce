package com.stockflow.inventory.api;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

/**
 * One carton (stock package) labelled on a goods-receipt line, as the receipt shows it (SCRUM-473).
 *
 * @param uomConversionId the packing unit it was made in; {@code nominalQty} is that unit's factor when it was made
 * @param quantity        what is in it now: what was counted into it while it is RECEIVING (null until counted);
 *                        once in stock, the units it stands for - its {@code nominalQty} while it is in stock,
 *                        nothing once it has left, since a carton holds no quantity of its own
 * @param countedAt       when it was counted in at receiving ({@code counted_at}, null until counted). A counted
 *                        carton stands for {@code nominalQty} units and that does not change once it is in stock, so
 *                        a receipt's loose part is its line less the {@code nominalQty} of its counted cartons,
 *                        whatever left them since
 * @param status          RECEIVING, IN_STOCK, CONSUMED or LOST ({@code inventory.stock_package.status}); a carton in
 *                        stock is whole, never opened (team plan 4C-3 dropped the flag that said so)
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
        Instant countedAt,
        String locationCode,
        String status
) {

    /** The units counted into it at receiving: its standard count once counted, none before. */
    public int countedUnits() {
        return countedAt == null ? 0 : nominalQty;
    }

    /**
     * The form an LPN is stored and matched in: trimmed and upper-cased, as it is printed - a scanner or a person
     * typing it may send it in either case. Blank is absent (null). Every command naming a carton by LPN goes
     * through this, and so does anything comparing a client's LPN with a stored one.
     */
    public static String normalLpn(String lpn) {
        return lpn == null || lpn.isBlank() ? null : lpn.strip().toUpperCase(Locale.ROOT);
    }

    /**
     * A command's list of cartons: each {@link #normalLpn normalised}, repeats dropped (an LPN in either case is one
     * carton), first-named order kept. A blank entry or an empty list names no carton, which is a caller's bug.
     */
    public static List<String> normalLpns(List<String> lpns) {
        Objects.requireNonNull(lpns, "lpns");
        List<String> normal = lpns.stream().map(StockPackageView::normalLpn).toList();
        if (normal.isEmpty() || normal.contains(null)) {
            throw new IllegalArgumentException("Name at least one carton, and no blank LPN");
        }
        return normal.stream().distinct().toList();
    }
}
