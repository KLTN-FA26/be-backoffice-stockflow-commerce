package com.stockflow.support;

import com.stockflow.procurement.internal.service.GoodsReceipts.NewPackage;

import java.util.Collections;
import java.util.List;

/**
 * The cartons of a receipt line as team plan 4C-2 wants them counted: every unit in a carton (kltn-docs 03 BR-12),
 * every carton holding its unit's standard count (BR-13). A demo SKU's {@code THUNG} unit holds its own standard count
 * (demo migration V20261206000100), so a line of n cartons is {@code of(n, factor)}.
 */
public final class ReceiptCartons {

    private ReceiptCartons() {
    }

    /** {@code count} new cartons (no LPN yet) of {@code factor} units each. */
    public static List<NewPackage> of(int count, int factor) {
        return Collections.nCopies(count, new NewPackage(null, factor));
    }

    /** The JSON {@code packages} array of a line request: {@code count} new cartons of {@code factor} units. */
    public static String json(int count, int factor) {
        StringBuilder out = new StringBuilder("[");
        for (int i = 0; i < count; i++) {
            out.append(i == 0 ? "" : ",").append("{\"lpn\":null,\"quantity\":").append(factor).append('}');
        }
        return out.append(']').toString();
    }
}
