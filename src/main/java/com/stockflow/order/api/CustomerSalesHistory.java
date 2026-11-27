package com.stockflow.order.api;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Collection;
import java.util.Map;
import java.util.UUID;

/**
 * What a customer was last sold each of its SKUs at: the unit price of the line of its most recent order whose contract
 * was signed. Published for the back-office assistant's price review on the quote price approval screen (assistant
 * contract §1.4): the Sales Manager deciding whether a price is too low weighs it against what this customer actually
 * agreed to pay last time, which neither the customer's price list nor the standard price says. Read-only, one
 * statement.
 */
public interface CustomerSalesHistory {

    /**
     * The last agreed price of one SKU.
     *
     * @param unitPrice      the price of one piece on that order line
     * @param soldOn         the day its contract was signed
     * @param cartons        the cartons of that line; null for a line not counted in cartons
     * @param unitsPerCarton with {@code cartons}
     */
    record LastSold(String sku, BigDecimal unitPrice, String currency, LocalDate soldOn, String orderNumber,
                    UUID orderId, Integer cartons, Integer unitsPerCarton) {
    }

    /**
     * The last sale of each of {@code skus} to {@code customerId}, by SKU as stored: the line of the order whose
     * contract was signed latest, among the customer's orders not cancelled. A SKU never sold to it is absent; null or
     * empty in, empty out.
     */
    Map<String, LastSold> lastSold(UUID customerId, Collection<String> skus);
}
