package com.stockflow.contracts;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Published by the <b>fulfillment</b> module when the carton check completes a shipment of an order whose installments
 * due before delivery are not paid in full (kltn-docs 08 §4.4 step 9, BR-11, §2: the warehouse manager "nhận cảnh báo
 * đợt BEFORE_DELIVERY chưa thu", §3 output "Nhân viên kho, Quản lý kho, Sale"). The completion is not blocked - the
 * handover is (09) - and the <b>notification</b> module tells, in-app, the warehouse managers of the shipment's
 * warehouse and the Sales account that answers for the order (owner decision RTM-PAYNOTIFY, 2026-11-19).
 *
 * <p>Published in the completion's transaction, once per shipment made; a completion answered again (a retry, a
 * completed check) publishes nothing. Ids, codes and the amount only - nothing anybody typed. Consumers receive it
 * in-process through {@code @ApplicationModuleListener}; see {@code package-info.java} for the rules on changing this
 * contract.</p>
 *
 * @param warehouseId  the warehouse the shipment waits at, whose managers are told
 * @param salesUserId  the Sales account that answers for the order (who sent the quote it came from); null when none
 *                     is known
 * @param amountDue    what the installments due before delivery still owe
 */
public record PaymentDueAtPacking(UUID eventId, UUID orderId, String orderNumber, UUID shipmentId, String shipmentCode,
                                  UUID warehouseId, UUID salesUserId, BigDecimal amountDue, String currency,
                                  Instant completedAt) {
}
