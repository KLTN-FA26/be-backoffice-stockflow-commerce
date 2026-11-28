package com.stockflow.chat.internal.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.stockflow.chat.internal.domain.CardKind;
import com.stockflow.chat.internal.domain.CardState;

import java.util.List;

/**
 * The actions a card offers, and to which side (kltn-docs 16 §3 Output, BR-03, BR-10, BR-11). Worked out from the card's
 * kind, its state and the document's status in its payload whenever a card is shown, so a client draws exactly the
 * buttons the server will accept - and never a button on a card that is no longer open.
 *
 * <ul>
 *   <li>QUOTE_REQUEST: Sales {@code CREATE_QUOTE}, only once every design on it is approved (BR-11);</li>
 *   <li>QUOTE: the customer {@code ACCEPT} (cartons per line) or {@code REQUEST_CHANGES} (feedback), while sent;</li>
 *   <li>SAMPLE_APPROVAL: the customer {@code APPROVE} or {@code REQUEST_REPRINT} (note required), while the round
 *       waits - never Sales (BR-10);</li>
 *   <li>CSAT_SURVEY: the customer {@code RATE} (1-5, comment);</li>
 *   <li>SHORTAGE_CHOICE: the customer {@code CHOOSE} (option, note), while the card is sent and nobody has chosen -
 *       never Sales, who carry the choice out on the order page (17 §4.14 steps 57-58, 16 BR-15);</li>
 *   <li>CONTRACT, PAYMENT_INFO, PAYMENT_REMINDER, INVOICE, ORDER_STATUS: none - they show, link and pay by transfer.</li>
 * </ul>
 */
public final class CardActions {

    public static final String CREATE_QUOTE = "CREATE_QUOTE";
    public static final String ACCEPT = "ACCEPT";
    public static final String REQUEST_CHANGES = "REQUEST_CHANGES";
    public static final String APPROVE = "APPROVE";
    public static final String REQUEST_REPRINT = "REQUEST_REPRINT";
    public static final String RATE = "RATE";
    public static final String CHOOSE = "CHOOSE";

    /** What each side may do on a card now. */
    public record Available(List<String> customer, List<String> staff) {
        static final Available NONE = new Available(List.of(), List.of());
    }

    private CardActions() {
    }

    public static Available of(CardKind kind, CardState state, JsonNode payload) {
        if (state != CardState.OPEN || payload == null) {
            return Available.NONE;
        }
        return switch (kind) {
            case QUOTE_REQUEST -> quotable(payload) ? new Available(List.of(), List.of(CREATE_QUOTE)) : Available.NONE;
            case QUOTE -> "SENT".equals(payload.path("status").asText())
                    ? new Available(List.of(ACCEPT, REQUEST_CHANGES), List.of()) : Available.NONE;
            case SAMPLE_APPROVAL -> "WAITING".equals(payload.path("status").asText())
                    ? new Available(List.of(APPROVE, REQUEST_REPRINT), List.of()) : Available.NONE;
            case CSAT_SURVEY -> new Available(List.of(RATE), List.of());
            case SHORTAGE_CHOICE -> "SENT".equals(payload.path("status").asText())
                    ? new Available(List.of(CHOOSE), List.of()) : Available.NONE;
            default -> Available.NONE;
        };
    }

    /**
     * Whether a quote request's card may be quoted: its request is open, and every design on it approved with the
     * customer's own SKU issued (BR-11; 14 BR-22).
     */
    static boolean quotable(JsonNode payload) {
        if (!payload.path("quoteId").isMissingNode() && !payload.path("quoteId").isNull()) {
            return false;
        }
        if ("PROJECT".equals(payload.path("scope").asText())) {
            if (!"OPEN".equals(payload.path("status").asText())) {
                return false;
            }
            JsonNode lines = payload.path("lines");
            if (!lines.isArray() || lines.isEmpty()) {
                return false;
            }
            for (JsonNode line : lines) {
                if (!approved(line)) {
                    return false;
                }
            }
            return true;
        }
        return "OPEN".equals(payload.path("requestStatus").asText()) && approved(payload);
    }

    private static boolean approved(JsonNode design) {
        return "APPROVED".equals(design.path("designStatus").asText()) && design.hasNonNull("customerSku")
                && !design.path("customerSku").asText().isBlank();
    }
}
