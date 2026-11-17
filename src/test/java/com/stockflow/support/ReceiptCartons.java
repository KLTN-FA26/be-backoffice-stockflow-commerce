package com.stockflow.support;

import com.stockflow.procurement.internal.service.GoodsReceipts.NewPackage;

import java.util.Collections;
import java.util.List;

/**
 * The cartons of a receipt line as team plan 4C-2 wants them counted: every unit in a carton (kltn-docs 03 BR-12),
 * every carton holding its unit's standard count (BR-13). The demo sofa and oak table have a {@code CARTON} unit of
 * one piece (demo migration V20261111000100), so a demo line of n pieces is n cartons of 1.
 */
public final class ReceiptCartons {

    /** The demo SKUs' carton unit: one piece to a carton. */
    public static final String DEMO_UNIT = "CARTON";

    private ReceiptCartons() {
    }

    /** {@code count} new cartons (no LPN yet) of {@code factor} units each. */
    public static List<NewPackage> of(int count, int factor) {
        return Collections.nCopies(count, new NewPackage(null, factor));
    }

    /** A demo line of {@code units} pieces: as many cartons of one. */
    public static List<NewPackage> demo(int units) {
        return of(units, 1);
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
