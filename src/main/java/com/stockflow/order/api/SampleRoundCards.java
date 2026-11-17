package com.stockflow.order.api;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * A print sample round as the chat's approval card shows it, and the customer's answer the card gives (kltn-docs 16
 * §4.5 steps 11-12, BR-10): the same operation as under "my designs" (13 §4.3 step 5), with the same checks - only the
 * customer who owns the production order answers, once per round, and a reprint request says why.
 */
public interface SampleRoundCards {

    /** A photo or video of the round; its link is asked for on the customer's sample page. */
    record Media(UUID id, String fileName, String contentType, boolean video) {
    }

    /** An earlier round and how it was answered, for the card's history. */
    record Earlier(UUID roundId, int roundNo, String result, Instant answeredAt) {
    }

    /**
     * @param status                {@code WAITING}, {@code APPROVED} or {@code REPRINT_REQUESTED}
     * @param productionOrderStatus the production order's own status, e.g. {@code SAMPLE_PENDING}, {@code CANCELLED}
     * @param skus                  the customer's SKUs the production order prints
     */
    record Card(UUID roundId, int roundNo, UUID productionOrderId, String productionOrderCode,
                String productionOrderStatus, UUID salesOrderId, UUID customerId, String status,
                LocalDate physicalSampleSentOn, Instant openedAt, Instant answeredAt, List<String> skus,
                List<Media> media, List<Earlier> earlier) {
    }

    Optional<Card> round(UUID roundId);

    /**
     * The customer's answer on the card.
     *
     * @param note required to ask for a reprint
     * @throws com.stockflow.common.error.BusinessException the round's own refusals: {@code SAMPLE_ROUND_NOT_FOUND} for
     *         another customer's or an inactive customer's, {@code SAMPLE_ROUND_ANSWERED},
     *         {@code PRODUCTION_ORDER_STATUS_INVALID}, {@code VALIDATION_FAILED} for a reprint without a note
     */
    Card answer(UUID customerId, UUID userId, UUID roundId, boolean approved, String note);
}
