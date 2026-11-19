package com.stockflow.notification.api;

/**
 * The sales order a production order was drawn up for, when it is still short of the goods that production order was
 * to bring (kltn-docs 17 §4.13 step 51: "đơn bán đang thiếu").
 *
 * @param orderNumber  the sales order's number
 * @param shortCartons the cartons it lacks of the production order's SKUs
 */
public record ShortSalesOrder(String orderNumber, int shortCartons) {
}
