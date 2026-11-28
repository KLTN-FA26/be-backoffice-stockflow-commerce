package com.stockflow.order.api;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The shortage choice card "Đơn thiếu N thùng" as the chat draws it, and what Sales and the customer do on it (kltn-docs
 * 17 §4.14, BR-33; 16 §4.7, BR-15). The card is a document of the order module: the chat posts it into a conversation
 * and shows it, and calls this for what the card says and for the customer's choice - it never decides what a card may
 * offer or whether a choice is valid.
 */
public interface ShortageCards {

    /**
     * The batch the card is about, as it stands now.
     *
     * @param windowFrom the first day of the delivery window in force; null when the batch has none
     * @param moved      whether Sales moved the window since the contract was signed (BR-28)
     */
    record Batch(int seq, LocalDate windowFrom, LocalDate windowTo, boolean moved) {
    }

    /** One order line the card is short of, frozen when it was sent. */
    record Line(String sku, String skuName, int batchCartons, int availableCartons, int missingCartons,
                int unitsPerCarton) {
    }

    /**
     * One way on, with the figures it showed when the card was sent. Only the figures of its own option are set; the
     * others are null.
     *
     * @param expectedDeliveryDate WAIT_FOR_PRODUCTION: when delivery resumes (D48)
     * @param afterWindow          WAIT_FOR_PRODUCTION: whether that day is after the batch's window - choosing it is then
     *                             the customer's consent to move the window there (BR-28)
     * @param amountReduced        REDUCE_CARTONS, SHIP_PART_CLOSE_REST: how much less the customer owes, with VAT
     * @param laterBatchesClosed   SHIP_PART_CLOSE_REST: the later batches the closure takes with it
     * @param refundUpTo           CANCEL_ORDER: what is refunded when no fee is kept (15 §4.6 step 18)
     * @param feePossible          CANCEL_ORDER: whether production has started, so Sales may agree a fee (BR-11)
     */
    record Option(ShortageOption option, LocalDate expectedDeliveryDate, Boolean afterWindow,
                  BigDecimal amountReduced, List<Integer> laterBatchesClosed, BigDecimal refundUpTo,
                  Boolean feePossible) {

        public Option {
            laterBatchesClosed = laterBatchesClosed == null ? null : List.copyOf(laterBatchesClosed);
        }

        public static Option waiting(LocalDate expectedDeliveryDate, boolean afterWindow) {
            return new Option(ShortageOption.WAIT_FOR_PRODUCTION, expectedDeliveryDate, afterWindow, null, null, null,
                    null);
        }

        public static Option reduce(BigDecimal amountReduced) {
            return new Option(ShortageOption.REDUCE_CARTONS, null, null, amountReduced, null, null, null);
        }

        public static Option shipPart(BigDecimal amountReduced, List<Integer> laterBatchesClosed) {
            return new Option(ShortageOption.SHIP_PART_CLOSE_REST, null, null, amountReduced, laterBatchesClosed, null,
                    null);
        }

        public static Option cancel(BigDecimal refundUpTo, boolean feePossible) {
            return new Option(ShortageOption.CANCEL_ORDER, null, null, null, null, refundUpTo, feePossible);
        }
    }

    /** What the customer chose: the option, when, and their note. */
    record Chosen(ShortageOption option, Instant at, String note) {
    }

    /**
     * The card. Nothing internal is in it (no LPN, bin, hold reason or internal note, 17 §4.14 step 55): the same view
     * serves the chat, the portal and the back office.
     *
     * @param code             the card's short code "TT-yyyyMMdd-NNNN" (UNH-CODE-1128): what people read, and what the
     *                         notes and the window history it writes name
     * @param version          the version the customer's choice must name; a stale one is refused
     * @param executionStarted SHIP_PART_CLOSE_REST: Sales started it
     * @param endedReason      for SUPERSEDED and EXPIRED, why
     * @param chatMessageId    the chat message the card was posted as; null until posted
     */
    record Card(UUID cardId, String code, UUID orderId, String orderNumber, UUID customerId,
                ShortageCardStatus status, long version, Batch batch, int missingCartons, List<Line> lines, List<Option> options,
                String salesNote, Instant sentAt, Chosen chosen, Instant executedAt, boolean executionStarted,
                ShortageCardEnd endedReason, UUID chatMessageId) {

        public Card {
            lines = List.copyOf(lines);
            options = List.copyOf(options);
        }
    }

    Optional<Card> card(UUID cardId);

    /**
     * Sales send the card for an order of the customer (the conversation's). The system draws it from the order: the
     * lines and counts come from the warehouse, the options from what each would do now, the figures from the
     * operations' own arithmetic - Sales type only a note (16 BR-15).
     *
     * @param replaceLive supersede the live card of the batch instead of refusing
     * @throws com.stockflow.common.error.BusinessException {@code NOT_FOUND} for an order that is not the customer's;
     *         {@code SHORTAGE_CARD_NOT_SENDABLE} (not held for a missing carton, nothing short, a live card exists)
     */
    Card send(UUID customerId, UUID orderId, String note, boolean replaceLive, UUID sentBy);

    /** Records the chat message the card was posted as. */
    void linkMessage(UUID cardId, UUID messageId);

    /**
     * The customer chooses one of the options the card offered: once per card, and the evidence of their consent.
     *
     * @param version the card's version the customer saw; null for the card's own
     * @throws com.stockflow.common.error.BusinessException {@code SHORTAGE_CARD_NOT_FOUND} for another customer's card;
     *         {@code SHORTAGE_CARD_STATE_CONFLICT} (stale, answered already or no longer live);
     *         {@code SHORTAGE_OPTION_NOT_AVAILABLE}
     */
    Card choose(UUID customerId, UUID userId, UUID cardId, ShortageOption option, String note, Long version);
}
