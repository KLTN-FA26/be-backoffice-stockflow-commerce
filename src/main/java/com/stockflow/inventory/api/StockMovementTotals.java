package com.stockflow.inventory.api;

/**
 * One SKU in one warehouse over a period, from the stock ledger (kltn-docs 20 §4.7, glossary "sổ biến động tồn";
 * 2026-11-14 plan, BE2-C): what it held at the start, what came in and went out by kind, what it held at the end. In
 * pieces (the base unit the ledger counts in); every in and out column is zero or more, an out column counting what
 * left. Opening and closing are the ledger's own sums, true as long as every change of stock wrote its line (20
 * BR-16): stock loaded as rows without one is missing from them, and a SKU whose only stock is such can even close
 * below zero ({@code tools/db/stock_ledger_balance.sql} lists them; {@code tools/db/qa/99_stock_ledger_balance.sql}
 * holds the demo data to it). Opening stock is loaded with an opening line - a {@code RECEIPT} of a
 * {@code STOCK_PACKAGE}, as the demo seed's got in V20261128000100 (open-questions C45) - which counts as "other in".
 *
 * <p>The columns add up: {@code closing = opening + received + returned + putBack - picked + adjustedIn - adjustedOut
 * + otherIn - otherOut}. "Other" is whatever the kinds above do not name - the movement types of removed flows
 * (transfers, scrap, supplier returns) and anything a later flow adds - so that the sum always holds.</p>
 *
 * @param warehousePrefix the warehouse, by the prefix its location codes start with ({@code HCM})
 * @param receivedUnits   goods received against production orders ({@code RECEIPT} of a {@code GOODS_RECEIPT_LINE})
 * @param returnedUnits   cartons a return receipt brought back ({@code RECEIPT} of a {@code GOODS_RECEIPT}, 4D-2)
 * @param putBackUnits    cartons taken by a pick and put back into stock ({@code PUT_BACK} from no location)
 * @param pickedUnits     goods picked out of stock for orders ({@code PICK})
 * @param adjustedInUnits approved adjustments and count corrections up ({@code ADJUSTMENT}, {@code COUNT_CORRECTION})
 */
public record StockMovementTotals(String warehousePrefix, String sku, long openingUnits, long receivedUnits,
                                  long returnedUnits, long putBackUnits, long pickedUnits, long adjustedInUnits,
                                  long adjustedOutUnits, long otherInUnits, long otherOutUnits, long closingUnits) {

    /** Whether every column is zero: nothing held at either end and nothing moved in the period. */
    public boolean isEmpty() {
        return openingUnits == 0 && receivedUnits == 0 && returnedUnits == 0 && putBackUnits == 0 && pickedUnits == 0
                && adjustedInUnits == 0 && adjustedOutUnits == 0 && otherInUnits == 0 && otherOutUnits == 0
                && closingUnits == 0;
    }
}
