package com.stockflow.order.internal.service;

import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.security.CurrentUser;
import com.stockflow.order.api.CancelOrderCommand;
import com.stockflow.order.api.CancellationFee;
import com.stockflow.order.api.CancellationReasonCode;
import com.stockflow.order.api.HoldReason;
import com.stockflow.order.api.OrderStatus;
import com.stockflow.order.api.ShortageCards;
import com.stockflow.order.api.ShortageOption;
import com.stockflow.order.internal.domain.Order;
import com.stockflow.order.internal.domain.OrderId;
import com.stockflow.order.internal.domain.OrderRepository;
import com.stockflow.order.internal.domain.ShortageCard;
import com.stockflow.order.internal.domain.ShortageCardRepository;
import com.stockflow.order.spi.ShortPickLines;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Locale;
import java.util.ResourceBundle;
import java.util.UUID;

/**
 * Sales carry out the option the customer chose on a shortage card (kltn-docs 17 §4.14 step 58, BR-33, D48, D49): each
 * option runs the operation that already exists for it, with that operation's own conditions, and the card records that
 * it was carried out. One transaction per press of "Thực hiện": a refusal of any step leaves the order, the stock and the
 * card as they were - the card stays {@code ANSWERED} and Sales see why.
 *
 * <ul>
 *   <li><b>Wait for production</b>: the short pick lines are given up (their holds released, D49), the INVENTORY hold
 *       is lifted - the batch now lacks the missing cartons, for the production coordinator's production order (17
 *       §4.3 step 6) - and, when the card's expected date is after the batch's window and Sales confirm it, the window
 *       moves to that date with the card as the consent (BR-28, D48). Card EXECUTED.</li>
 *   <li><b>Ship part, close the rest</b>, in two steps: first the short lines are given up, partial shipment allowed and
 *       the hold lifted (execution started); once the warehouse completed the partial check, Sales close the rest
 *       ({@link #closeRest}) - the remainder closure, whose own hook marks the card EXECUTED
 *       ({@link ShortageCardService#remainderClosed}).</li>
 *   <li><b>Cancel</b>: the short lines are given up, then the staff cancellation with reason {@code OUT_OF_STOCK} and the
 *       fee as Sales agreed it (17 §4.8 step 30, fee rule unchanged). Card EXECUTED.</li>
 * </ul>
 * "Reduce the cartons" is carried out elsewhere (BE-3).
 *
 * <p><b>Locks</b>, every step: the order's pick tasks first ({@link ShortPickLines#lockTasksOf}), then the order row, then
 * the card, then whatever the inner operation takes (SKU advisory locks, cartons) - pick before order, as a picker's
 * report and the warehouse's reallocation lock them (BE {@code CLAUDE.md} §4 Picking). A second press finds the card no
 * longer ANSWERED (or the version moved on) and is refused 409; the step of "ship part" already started answers the
 * card as it is.</p>
 *
 * <p>The inner operations are called on their services directly, not through {@link OrderOperations}, so the order's
 * history gets one "làm hộ" line for the press ({@code EXECUTE_SHORTAGE_CHOICE}, recorded by {@link OrderOperations}),
 * not one per step.</p>
 */
@Service
@Transactional
public class ShortageCardExecutionService {

    private static final Logger log = LoggerFactory.getLogger(ShortageCardExecutionService.class);

    /** What a wait carried out does to the window (D48). */
    public record WindowMove(boolean move, LocalDate windowEnd) {
        public static WindowMove none() {
            return new WindowMove(false, null);
        }
    }

    private final OrderRepository orders;
    private final ShortageCardRepository cards;
    private final ShortageCardService shortageCards;
    private final OrderServiceImpl orderService;
    private final PartialShipments partialShipments;
    private final DeliveryBatchService batches;
    private final ObjectProvider<ShortPickLines> shortLines;
    private final Clock clock;

    ShortageCardExecutionService(OrderRepository orders, ShortageCardRepository cards,
                                 ShortageCardService shortageCards, OrderServiceImpl orderService,
                                 PartialShipments partialShipments, DeliveryBatchService batches,
                                 ObjectProvider<ShortPickLines> shortLines, Clock clock) {
        this.orders = orders;
        this.cards = cards;
        this.shortageCards = shortageCards;
        this.orderService = orderService;
        this.partialShipments = partialShipments;
        this.batches = batches;
        this.shortLines = shortLines;
        this.clock = clock;
    }

    /**
     * Wait for production (17 §4.14 step 58, D48, D49).
     *
     * @param move   whether to move the window to the card's expected date too: only when the card said that date is
     *               after the batch's window ({@code afterWindow}); the new window ends on {@code move.windowEnd()}
     * @param note   Sales' own words, after the system's "theo lựa chọn của khách…"; optional
     * @throws BusinessException {@code SHORTAGE_CARD_NOT_FOUND}, {@code SHORTAGE_CARD_STATE_CONFLICT},
     *         {@code SHORTAGE_OPTION_NOT_AVAILABLE}, {@code VALIDATION_FAILED}, or the inner operation's refusal
     *         (BR-28: {@code DELIVERY_BATCH_WINDOW_LOCKED} …) - nothing changes
     */
    public ShortageCards.Card waitForProduction(CurrentUser user, UUID orderId, UUID cardId, Long version,
                                                WindowMove move, String note) {
        UUID by = user.userId();
        Locked locked = lock(orderId, cardId, version, ShortageOption.WAIT_FOR_PRODUCTION);
        requireHeldForStock(locked.order());
        ShortageCard card = locked.card();
        WindowMove asked = move == null ? WindowMove.none() : move;
        if (asked.move()) {
            boolean after = card.options().stream()
                    .filter(o -> o.option() == ShortageOption.WAIT_FOR_PRODUCTION)
                    .anyMatch(o -> Boolean.TRUE.equals(o.afterWindow()));
            if (!after) {
                throw new BusinessException(ErrorCode.VALIDATION_FAILED, ("Shortage card %s: its expected date is not "
                        + "after the batch's window - move the window with the customer's consent as usual (17 BR-28)")
                        .formatted(cardId)).localized("refusal.shortage.windowMoveNotOffered");
            }
            if (asked.windowEnd() == null || asked.windowEnd().isBefore(card.expectedDeliveryDate())) {
                throw new BusinessException(ErrorCode.VALIDATION_FAILED, "The new window ends on or after %s"
                        .formatted(card.expectedDeliveryDate()));
            }
        }
        String text = noteOf(card, note);
        giveUpShortLines(orderId, by);
        orderService.resolveHold(orderId, text, by, null);
        if (asked.move()) {
            batches.changeWindowOnShortageCard(user, orderId, card.deliveryBatchId(), card.expectedDeliveryDate(),
                    asked.windowEnd(), text, card.id());
        }
        return executed(card, orderId, by);
    }

    /**
     * Ship part, close the rest - step 1 (17 §4.14 step 58): the short lines given up, partial shipment allowed (nothing
     * changes when it is already), the INVENTORY hold lifted, the card's execution started. The warehouse then completes
     * the partial check and Sales are told to close the rest ({@code CLOSE_REST_DUE}). Pressed again once started, it
     * answers the card as it is.
     */
    public ShortageCards.Card shipPart(UUID orderId, UUID cardId, Long version, String note, UUID by) {
        Locked locked = lock(orderId, cardId, null, ShortageOption.SHIP_PART_CLOSE_REST);
        ShortageCard card = locked.card();
        if (card.executionStartedAt() != null) {
            return shortageCards.card(cardId).orElseThrow();
        }
        requireVersion(card, version);
        requireHeldForStock(locked.order());
        String text = noteOf(card, note);
        giveUpShortLines(orderId, by);
        partialShipments.decide(orderId, true, text, by);
        Order now = orders.findByIdForUpdate(new OrderId(orderId)).orElseThrow();
        if (now.status() == OrderStatus.ON_HOLD && now.holdReason() == HoldReason.INVENTORY) {
            orderService.resolveHold(orderId, text, by, null);
        }
        Instant at = clock.instant();
        card.startExecution(by, at);
        ShortageCard saved = cards.save(card);
        shortageCards.announce(saved, orders.findById(new OrderId(orderId)).orElse(null), "EXECUTION_STARTED", at);
        log.info("Order {}: ship part of shortage card {} started by {}", orderId, cardId, by);
        return shortageCards.card(cardId).orElseThrow();
    }

    /**
     * Ship part, close the rest - step 2 (17 §4.14 step 58, §4.8 step 33): the remainder closure as the order's own
     * endpoint runs it (the labelled cartons not handed over stay and are delivered), with its fee rule; the closure marks
     * the card EXECUTED. Only once step 1 was carried out.
     */
    public ShortageCards.Card closeRest(UUID orderId, UUID cardId, Long version, CancellationReasonCode reasonCode,
                                        String note, CancellationFee fee, UUID by) {
        Locked locked = lock(orderId, cardId, version, ShortageOption.SHIP_PART_CLOSE_REST);
        ShortageCard card = locked.card();
        if (card.executionStartedAt() == null) {
            throw new BusinessException(ErrorCode.SHORTAGE_CARD_STATE_CONFLICT, ("Shortage card %s: ship the part first, "
                    + "then close the rest").formatted(cardId)).localized("refusal.shortage.closeRestNotStarted");
        }
        if (reasonCode == null) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "reasonCode is required");
        }
        orderService.closeRemainder(orderId, reasonCode, noteOf(card, note), fee, by);
        return shortageCards.card(cardId).orElseThrow();
    }

    /**
     * Cancel the order (17 §4.14 step 58, §4.8 step 30): the short lines given up, then the staff cancellation with
     * reason {@code OUT_OF_STOCK}; a fee - a share kept or an amount agreed - only as that cancellation allows it. The
     * card is marked carried out first, inside the same transaction, so the cancellation does not end it as "cancelled by
     * another route"; a refused cancellation rolls both back.
     */
    public ShortageCards.Card cancel(UUID orderId, UUID cardId, Long version, String note, BigDecimal retainedPercent,
                                     CancellationFee fee, UUID by) {
        Locked locked = lock(orderId, cardId, version, ShortageOption.CANCEL_ORDER);
        ShortageCard card = locked.card();
        String text = noteOf(card, note);
        giveUpShortLines(orderId, by);
        Instant at = clock.instant();
        card.executed(by, at);
        ShortageCard saved = cards.save(card);
        orderService.cancel(orderId, new CancelOrderCommand(CancellationReasonCode.OUT_OF_STOCK, text, retainedPercent,
                by, fee));
        shortageCards.announce(saved, orders.findById(new OrderId(orderId)).orElse(null), "EXECUTED", at);
        log.info("Order {}: cancelled on shortage card {} by {}", orderId, cardId, by);
        return shortageCards.card(cardId).orElseThrow();
    }

    // ---- helpers ------------------------------------------------------------------------------------------------------

    private record Locked(Order order, ShortageCard card) {
    }

    /**
     * Pick tasks, order, card - in that order - and the card's checks: it is the order's, ANSWERED, the customer chose
     * {@code option}, and {@code version} (when given) is the card's.
     */
    private Locked lock(UUID orderId, UUID cardId, Long version, ShortageOption option) {
        ShortPickLines warehouse = shortLines.getIfAvailable();
        if (warehouse != null) {
            warehouse.lockTasksOf(orderId);
        }
        Order order = orders.findByIdForUpdate(new OrderId(orderId))
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "No order with id " + orderId));
        ShortageCard card = cards.findForUpdate(cardId).filter(c -> c.orderId().equals(orderId))
                .orElseThrow(() -> new BusinessException(ErrorCode.SHORTAGE_CARD_NOT_FOUND,
                        "No shortage card %s on order %s".formatted(cardId, orderId)));
        requireVersion(card, version);
        if (card.status() != com.stockflow.order.api.ShortageCardStatus.ANSWERED) {
            throw new BusinessException(ErrorCode.SHORTAGE_CARD_STATE_CONFLICT, ("Shortage card %s is %s: only a card the "
                    + "customer chose on, not carried out yet, is carried out").formatted(cardId, card.status()))
                    .localized("refusal.shortage.stateConflict");
        }
        if (card.chosenOption() != option) {
            throw new BusinessException(ErrorCode.SHORTAGE_OPTION_NOT_AVAILABLE, ("On shortage card %s the customer chose "
                    + "%s, not %s").formatted(cardId, card.chosenOption(), option))
                    .localized("refusal.shortage.otherOptionChosen");
        }
        return new Locked(order, card);
    }

    private static void requireVersion(ShortageCard card, Long version) {
        if (version != null && version != card.version()) {
            throw new BusinessException(ErrorCode.SHORTAGE_CARD_STATE_CONFLICT,
                    "Shortage card %s is at version %d, not %d".formatted(card.id(), card.version(), version))
                    .localized("refusal.shortage.stateConflict");
        }
    }

    /** The option lifts the INVENTORY hold the card was sent for: an order no longer held so has nothing to lift. */
    private static void requireHeldForStock(Order order) {
        if (order.status() != OrderStatus.ON_HOLD || order.holdReason() != HoldReason.INVENTORY) {
            throw new BusinessException(ErrorCode.SHORTAGE_CARD_STATE_CONFLICT, ("Order %s is %s: it is no longer held "
                    + "for a missing carton").formatted(order.orderNumber(), order.status()))
                    .localized("refusal.shortage.notOnHold");
        }
    }

    private void giveUpShortLines(UUID orderId, UUID by) {
        ShortPickLines warehouse = shortLines.getIfAvailable();
        if (warehouse != null) {
            warehouse.cancelShortLines(orderId, by);
        }
    }

    private ShortageCards.Card executed(ShortageCard card, UUID orderId, UUID by) {
        Instant at = clock.instant();
        card.executed(by, at);
        ShortageCard saved = cards.save(card);
        shortageCards.announce(saved, orders.findById(new OrderId(orderId)).orElse(null), "EXECUTED", at);
        log.info("Order {}: shortage card {} ({}) carried out by {}", orderId, card.id(), card.chosenOption(), by);
        return shortageCards.card(card.id()).orElseThrow();
    }

    /**
     * The note of each step it runs (17 §4.14 step 58): "Theo lựa chọn của khách trên thẻ thiếu thùng" with the card, then
     * Sales' own words when they gave some. System text from the Vietnamese bundle, as the order's history shows it.
     */
    static String noteOf(ShortageCard card, String salesNote) {
        ResourceBundle texts = ResourceBundle.getBundle("i18n.messages", Locale.forLanguageTag("vi"));
        String note = texts.getString("shortage.execution.note").formatted(card.id());
        String own = salesNote == null || salesNote.isBlank() ? null : salesNote.strip();
        return own == null ? note : note + ". " + own;
    }
}
