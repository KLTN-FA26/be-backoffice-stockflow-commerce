package com.stockflow.production.api;

import java.time.Instant;
import java.time.LocalDate;

/**
 * One production order (LSX) as {@link ProductionOrderQueries} returns it: where it stands and what it is for, in the
 * numbers people use on the shop floor and on the phone with a customer.
 *
 * @param orderNumber         {@code LSX-yyyyMMdd-nnnn}
 * @param type                {@code ORDER}, one printed line of a sales order, or {@code SAMPLE}, one sample round
 * @param mode                {@code IN_HOUSE}, or {@code SUBCONTRACTED}: printed by an outside print shop
 * @param status              one of {@link ProductionOrderQueries#STATUSES}
 * @param holdReason          one of {@link ProductionOrderQueries#HOLD_REASONS} while ON_HOLD; null otherwise
 * @param warehouseCode       the prefix of the warehouse whose print shop makes it ({@code HCM})
 * @param blankSku            the blank it prints on
 * @param plannedQuantity     the good units it must deliver
 * @param goodQuantity        good units printed so far; QC may still fail some of them, so this is not finished goods
 * @param dueDate             null when none was set
 * @param overdue             open (not COMPLETED or CANCELLED) and due before today, Asia/Ho_Chi_Minh
 * @param salesOrderNumber    the sales order it prints for; null for a SAMPLE
 * @param sampleRequestNumber the sample request it prints a round of; null for an ORDER
 * @param sampleRound         that round, 1 for the first; null for an ORDER
 * @param createdAt           when its line was released to production, or its sample round submitted
 */
public record ProductionOrderView(String orderNumber, String type, String mode, String status, String holdReason,
                                  String warehouseCode, String blankSku, int plannedQuantity, int goodQuantity,
                                  LocalDate dueDate, boolean overdue, String salesOrderNumber,
                                  String sampleRequestNumber, Integer sampleRound, Instant createdAt) {
}
