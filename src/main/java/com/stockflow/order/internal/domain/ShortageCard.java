package com.stockflow.order.internal.domain;

import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.order.api.ShortageCardEnd;
import com.stockflow.order.api.ShortageCardStatus;
import com.stockflow.order.api.ShortageCards;
import com.stockflow.order.api.ShortageOption;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * The shortage choice card "Đơn thiếu N thùng" (kltn-docs 17 §4.14 steps 55-60, §5.6, BR-33; 16 BR-15): the document Sales
 * send into the customer's chat for an order held INVENTORY by missing cartons the warehouse could not replace.
 *
 * <p>Why a document of its own and not a field of the order. The customer's press on one of the offered options is the
 * evidence of consent for what Sales do next (re-date the batch, drop cartons, close the rest, cancel): who pressed,
 * when, which option and what the card said then - the cartons and the money - are kept as they were, whatever the order
 * does afterwards. Sales still carry the choice out with the operations that exist; the card does not do them.</p>
 *
 * <pre>
 *   SENT ──choose──▶ ANSWERED ──executed──▶ EXECUTED
 *     └───────┬─────────┘
 *             └─ a newer card ▶ SUPERSEDED        nothing left to decide ▶ EXPIRED
 * </pre>
 *
 * <p>Only the customer chooses, once per card; a change of mind is a new card (the old one is superseded). The card never
 * expires by time (D47). Every rule here is stated again by the table's CHECKs ({@code ck_shortage_card_*}) and its
 * partial unique index of one live card per batch.</p>
 */
public final class ShortageCard {

    /** The cartons of one order line the card is short of, as the card showed them. */
    public record Line(UUID id, UUID orderLineId, String sku, String skuName, int batchCartons, int availableCartons,
                       int missingCartons, int unitsPerCarton) {

        public Line {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(orderLineId, "orderLineId");
            Objects.requireNonNull(sku, "sku");
            if (batchCartons <= 0 || availableCartons < 0 || missingCartons <= 0 || unitsPerCarton <= 0
                    || availableCartons + missingCartons != batchCartons) {
                throw new IllegalArgumentException("A shortage line's cartons: the batch's, those available and those "
                        + "missing, adding up");
            }
        }
    }

    /** Where the wait option's expected date came from; shown to staff only. */
    public enum DateSource { PRODUCTION_ORDER, STANDARD_LEAD_TIME }

    /** Matches {@code ck_shortage_card_code}: "TT-" + the Vietnam day it was sent + a per-day number (UNH-CODE-1128). */
    private static final java.util.regex.Pattern CODE = java.util.regex.Pattern.compile("TT-\\d{8}-\\d{4,}");

    private final UUID id;
    private final String code;
    private final UUID orderId;
    private final UUID deliveryBatchId;
    private final UUID customerId;
    private ShortageCardStatus status;
    private final List<Line> lines;
    private final List<ShortageCards.Option> options;
    private final int missingCartons;
    private final LocalDate expectedDeliveryDate;
    private final DateSource expectedDateSource;
    private final String salesNote;
    private final UUID sentBy;
    private final Instant sentAt;
    private ShortageOption chosenOption;
    private UUID chosenBy;
    private Instant chosenAt;
    private String customerNote;
    private Instant executionStartedAt;
    private UUID executedBy;
    private Instant executedAt;
    private ShortageCardEnd endedReason;
    private UUID supersededBy;
    private final UUID chatMessageId;
    private final long version;

    /** Restores one as stored. */
    public ShortageCard(UUID id, String code, UUID orderId, UUID deliveryBatchId, UUID customerId,
                        ShortageCardStatus status, List<Line> lines, List<ShortageCards.Option> options,
                        LocalDate expectedDeliveryDate,
                        DateSource expectedDateSource, String salesNote, UUID sentBy, Instant sentAt,
                        ShortageOption chosenOption, UUID chosenBy, Instant chosenAt, String customerNote,
                        Instant executionStartedAt, UUID executedBy, Instant executedAt, ShortageCardEnd endedReason,
                        UUID supersededBy, UUID chatMessageId, long version) {
        this.id = Objects.requireNonNull(id, "id");
        if (code == null || !CODE.matcher(code).matches()) {
            throw new IllegalArgumentException("A shortage card's code is TT-yyyyMMdd-NNNN, not " + code);
        }
        this.code = code;
        this.orderId = Objects.requireNonNull(orderId, "orderId");
        this.deliveryBatchId = Objects.requireNonNull(deliveryBatchId, "deliveryBatchId");
        this.customerId = Objects.requireNonNull(customerId, "customerId");
        this.status = Objects.requireNonNull(status, "status");
        this.lines = List.copyOf(lines);
        this.options = List.copyOf(options);
        this.expectedDeliveryDate = expectedDeliveryDate;
        this.expectedDateSource = expectedDateSource;
        this.salesNote = salesNote;
        this.sentBy = Objects.requireNonNull(sentBy, "sentBy");
        this.sentAt = Objects.requireNonNull(sentAt, "sentAt");
        this.chosenOption = chosenOption;
        this.chosenBy = chosenBy;
        this.chosenAt = chosenAt;
        this.customerNote = customerNote;
        this.executionStartedAt = executionStartedAt;
        this.executedBy = executedBy;
        this.executedAt = executedAt;
        this.endedReason = endedReason;
        this.supersededBy = supersededBy;
        this.chatMessageId = chatMessageId;
        this.version = version;
        if (this.lines.isEmpty() || this.options.isEmpty()) {
            throw new IllegalArgumentException("A shortage card is about cartons that are missing and offers a way on");
        }
        this.missingCartons = this.lines.stream().mapToInt(Line::missingCartons).sum();
        boolean waits = offers(ShortageOption.WAIT_FOR_PRODUCTION);
        if (waits != (expectedDeliveryDate != null) || (expectedDeliveryDate == null) != (expectedDateSource == null)) {
            throw new IllegalArgumentException("The wait option carries its expected delivery date, and only it");
        }
    }

    /**
     * The card Sales send: SENT, the lines and the options frozen as the system drew them.
     *
     * @param code    the card's short code, "TT-yyyyMMdd-NNNN" (UNH-CODE-1128): what people read and the notes it writes
     *                name, never its id
     * @param options the options the card offers with the figures they show, in the order they are shown
     */
    public static ShortageCard send(UUID id, String code, UUID orderId, UUID deliveryBatchId, UUID customerId,
                                    List<Line> lines, List<ShortageCards.Option> options,
                                    LocalDate expectedDeliveryDate,
                                    DateSource expectedDateSource, String salesNote, UUID sentBy, Instant now) {
        String note = salesNote == null || salesNote.isBlank() ? null : salesNote.strip();
        if (note != null && note.length() > 500) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "The note is at most 500 characters");
        }
        return new ShortageCard(id, code, orderId, deliveryBatchId, customerId, ShortageCardStatus.SENT, lines, options,
                expectedDeliveryDate, expectedDateSource, note, sentBy, now, null, null, null, null, null, null, null,
                null, null, null, 0L);
    }

    /**
     * The customer chooses {@code option}, once (17 §4.14 step 57): from SENT only, and one the card offered.
     *
     * @param note optional, at most 500 characters
     * @throws BusinessException {@code SHORTAGE_CARD_STATE_CONFLICT} when the card is not SENT;
     *         {@code SHORTAGE_OPTION_NOT_AVAILABLE} for an option the card did not offer
     */
    public void choose(ShortageOption option, UUID customerUserId, String note, Instant now) {
        Objects.requireNonNull(option, "option");
        Objects.requireNonNull(customerUserId, "customerUserId");
        if (status != ShortageCardStatus.SENT) {
            throw new BusinessException(ErrorCode.SHORTAGE_CARD_STATE_CONFLICT,
                    "Shortage card %s is %s: it takes one choice, while it is sent".formatted(id, status))
                    .localized("refusal.shortage.stateConflict");
        }
        if (!offers(option)) {
            throw new BusinessException(ErrorCode.SHORTAGE_OPTION_NOT_AVAILABLE,
                    "Shortage card %s did not offer %s".formatted(id, option))
                    .localized("refusal.shortage.optionNotAvailable");
        }
        String trimmed = note == null || note.isBlank() ? null : note.strip();
        if (trimmed != null && trimmed.length() > 500) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "The note is at most 500 characters");
        }
        this.status = ShortageCardStatus.ANSWERED;
        this.chosenOption = option;
        this.chosenBy = customerUserId;
        this.chosenAt = now;
        this.customerNote = trimmed;
    }

    /**
     * Sales started carrying the chosen option out in steps (SHIP_PART_CLOSE_REST step 1): from ANSWERED only; from here
     * the card ends only if the order is cancelled ({@link #expire}).
     */
    public void startExecution(UUID by, Instant now) {
        requireAnswered();
        Objects.requireNonNull(by, "by");
        if (executionStartedAt == null) {
            this.executionStartedAt = now;
        }
    }

    /** Sales carried the chosen option out: ANSWERED to EXECUTED. */
    public void executed(UUID by, Instant now) {
        requireAnswered();
        this.status = ShortageCardStatus.EXECUTED;
        this.executedBy = Objects.requireNonNull(by, "by");
        this.executedAt = now;
    }

    /** A newer card of the batch replaced this one; from SENT or ANSWERED. */
    public void supersede(UUID newCardId) {
        requireLive();
        this.status = ShortageCardStatus.SUPERSEDED;
        this.supersededBy = Objects.requireNonNull(newCardId, "newCardId");
        this.endedReason = ShortageCardEnd.REPLACED;
    }

    /**
     * Nothing is left to decide with the card: from SENT or ANSWERED. A card whose execution started stays - it is being
     * carried out - unless the order itself was cancelled (17 §4.14 step 60).
     *
     * @return whether the card ended
     */
    public boolean expire(ShortageCardEnd reason) {
        Objects.requireNonNull(reason, "reason");
        if (reason == ShortageCardEnd.REPLACED) {
            throw new IllegalArgumentException("A card replaced is superseded, not expired");
        }
        if (!status.live() || executionStartedAt != null && reason != ShortageCardEnd.ORDER_CANCELLED) {
            return false;
        }
        this.status = ShortageCardStatus.EXPIRED;
        this.endedReason = reason;
        return true;
    }

    private void requireAnswered() {
        if (status != ShortageCardStatus.ANSWERED) {
            throw new BusinessException(ErrorCode.SHORTAGE_CARD_STATE_CONFLICT,
                    "Shortage card %s is %s: the customer has not chosen, or it ended".formatted(id, status))
                    .localized("refusal.shortage.stateConflict");
        }
    }

    private void requireLive() {
        if (!status.live()) {
            throw new BusinessException(ErrorCode.SHORTAGE_CARD_STATE_CONFLICT,
                    "Shortage card %s is %s".formatted(id, status)).localized("refusal.shortage.stateConflict");
        }
    }

    public boolean offers(ShortageOption option) {
        return options.stream().anyMatch(o -> o.option() == option);
    }

    public UUID id() { return id; }
    public String code() { return code; }
    public UUID orderId() { return orderId; }
    public UUID deliveryBatchId() { return deliveryBatchId; }
    public UUID customerId() { return customerId; }
    public ShortageCardStatus status() { return status; }
    public List<Line> lines() { return lines; }
    public List<ShortageCards.Option> options() { return options; }
    public int missingCartons() { return missingCartons; }
    public LocalDate expectedDeliveryDate() { return expectedDeliveryDate; }
    public DateSource expectedDateSource() { return expectedDateSource; }
    public String salesNote() { return salesNote; }
    public UUID sentBy() { return sentBy; }
    public Instant sentAt() { return sentAt; }
    public ShortageOption chosenOption() { return chosenOption; }
    public UUID chosenBy() { return chosenBy; }
    public Instant chosenAt() { return chosenAt; }
    public String customerNote() { return customerNote; }
    public Instant executionStartedAt() { return executionStartedAt; }
    public UUID executedBy() { return executedBy; }
    public Instant executedAt() { return executedAt; }
    public ShortageCardEnd endedReason() { return endedReason; }
    public UUID supersededBy() { return supersededBy; }
    public UUID chatMessageId() { return chatMessageId; }
    public long version() { return version; }
}
