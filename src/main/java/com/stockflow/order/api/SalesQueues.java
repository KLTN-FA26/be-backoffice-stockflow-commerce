package com.stockflow.order.api;

import com.stockflow.common.api.PageResponse;
import com.stockflow.common.security.CurrentUser;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Set;
import java.util.UUID;

/**
 * What waits on someone in the order module, counted, for another module to show (the back-office assistant's work
 * queue, contract 3.1): the delivery batches due to ship today, the contracts waiting for accounting's credit decision,
 * and the quotes waiting for the Sales Manager to approve their prices. The first rows and the count of all; read
 * only.
 *
 * <p>The last two are the very lists their screens read ({@code GET /orders/contract-credit-reviews},
 * {@code GET /quotes?priceReview=PENDING}), so a count here is the count the screen shows. The first has no screen of
 * its own - a batch is shown on its order's page - and is defined here once: see {@link #batchesDue}.</p>
 */
public interface SalesQueues {

    /** The most rows one call returns. */
    int MAX_ROWS = 50;

    /**
     * The delivery batches whose window includes {@code day} and of which nothing has left yet, of an order still
     * owing deliveries - confirmed and not yet all handed over, or on hold from such a status
     * ({@code DelayAlertService.DELIVERY_FOLLOWED}) - the earliest window end first. "Nothing has left" is: no
     * shipment was handed over for the batch ({@code order_shipment.delivery_batch_id}); a batch partly shipped is
     * not listed, and its order's page shows what is left of it. Only the batches of the order's live contract: one
     * cancelled before signing keeps its batches, which nobody owes. The warehouse manager's shift brief starts here
     * (17 §4.5: every order ships as a full truckload, batch by batch).
     *
     * @param warehouseIds the orders shipping from (or released to) one of these warehouses, as the order list's
     *                     warehouse rule; null for no limit, empty for none
     * @param limit        1..{@value #MAX_ROWS}; the page's total counts every batch due
     */
    PageResponse<BatchDue> batchesDue(LocalDate day, Set<UUID> warehouseIds, int limit);

    /**
     * Contracts not sent because their credit check failed, waiting for accounting (15 BR-11), oldest first - the
     * queue of {@code GET /orders/contract-credit-reviews}. Call it inside that screen's guard
     * ({@code sales-order-credit-holds:READ}).
     */
    PageResponse<CreditReviewWaiting> creditReviewsWaiting(int limit);

    /**
     * Draft quotes waiting for the Sales Manager to approve their prices (14 BR-10), as
     * {@code GET /quotes?priceReview=PENDING} lists them for {@code user}. Call it inside that screen's guard
     * ({@code sales-quotes:READ}).
     */
    PageResponse<QuoteWaiting> quotesAwaitingPriceApproval(CurrentUser user, int limit);

    /**
     * @param orderStatus        the order's status (an order ON_HOLD is still listed: its batch is due all the same)
     * @param shippingWarehouseId where it ships from; null before Sales chose it
     */
    record BatchDue(UUID orderId, String orderNumber, UUID customerId, String orderStatus, UUID deliveryBatchId,
                    int batchSeq, LocalDate windowStart, LocalDate windowEnd, UUID shippingWarehouseId) {
    }

    record CreditReviewWaiting(UUID orderId, String orderNumber, String contractNumber, UUID customerId,
                               Instant submittedAt) {
    }

    record QuoteWaiting(UUID quoteId, String quoteNumber, UUID customerId, Instant createdAt) {
    }
}
