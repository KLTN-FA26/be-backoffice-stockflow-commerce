package com.stockflow.contracts;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Published by the <b>order</b> module when the Sales Manager approves a production order, which sends it (kltn-docs 17
 * §4.3 step 7, BR-23): once per production order. The <b>notification</b> module sends it to the producer through the
 * producer's channel - by e-mail, with links to the slip and the print files; nothing for a producer who is handed the
 * slip - and tells the warehouse manager(s) of the receiving warehouse what is coming (00 §6: cartons, volume, need-by
 * date).
 *
 * <p>Facts only. The producer's address, the coordinator's note and the lines are read when the e-mail is written,
 * through {@code notification.api.ProductionOrderDirectory}, which the order module implements: an event is stored, and
 * neither a person's address nor typed text belongs in it. Consumers receive it in-process through
 * {@code @ApplicationModuleListener}; see {@code package-info.java} for the rules on changing this contract.</p>
 *
 * @param totalCartons the cartons of every line
 * @param volumeM3     their volume in cubic metres, from each SKU's carton size; null when a SKU has none recorded
 */
public record ProductionOrderSent(UUID eventId, UUID productionOrderId, String code, UUID receivingWarehouseId,
                                  LocalDate needByDate, int totalCartons, BigDecimal volumeM3, Instant sentAt) {
}
