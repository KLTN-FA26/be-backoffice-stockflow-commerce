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
import com.stockflow.order.internal.domain.DeliveryBatch;
import com.stockflow.order.internal.domain.Order;
import com.stockflow.order.internal.domain.OrderId;
import com.stockflow.order.internal.domain.OrderRepository;
import com.stockflow.order.internal.domain.SalesContract;
import com.stockflow.order.internal.domain.SalesContractRepository;
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
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
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
 *   <li><b>Reduce the cartons</b> ("Giảm số thùng", D45, D51, D52; BE-3): the short pick lines are given up, exactly the
 *       missing cartons of the card's batch come off the order's lines and that batch (the schedule in force; the signed
 *       contract keeps its cartons, no annex), the installments owe less by their value with VAT and no fee (15 §4.6
 *       step 19) - surplus refundable - and the INVENTORY hold is lifted; the batch goes on with what it has. A batch left
 *       with nothing is closed like a closure of the rest for that batch (D52): later batches are released as after a
 *       batch handed over, the last one ends the order by the closure's rule, and the open production orders of that
 *       batch are stopped as a closure stops them. Card EXECUTED.</li>
 * </ul>
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
    private final SalesContractRepository contracts;
    private final OrderEventPublisher events;
    private final Clock clock;

    ShortageCardExecutionService(OrderRepository orders, ShortageCardRepository cards,
                                 ShortageCardService shortageCards, OrderServiceImpl orderService,
                                 PartialShipments partialShipments, DeliveryBatchService batches,
                                 ObjectProvider<ShortPickLines> shortLines, SalesContractRepository contracts,
                                 OrderEventPublisher events, Clock clock) {
        this.orders = orders;
        this.cards = cards;
        this.shortageCards = shortageCards;
        this.orderService = orderService;
        this.partialShipments = partialShipments;
        this.batches = batches;
        this.shortLines = shortLines;
        this.contracts = contracts;
        this.events = events;
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
     * another route"; a refused cancellation rolls both back. The fee has its bearer, Sales' choice with no default
     * (D46, D50): an agreed fee names its own, {@code feeBearer} is the bearer of a share.
     */
    public ShortageCards.Card cancel(UUID orderId, UUID cardId, Long version, String note, BigDecimal retainedPercent,
                                     com.stockflow.order.api.CancellationFeeBearer feeBearer, CancellationFee fee,
                                     UUID by) {
        Locked locked = lock(orderId, cardId, version, ShortageOption.CANCEL_ORDER);
        ShortageCard card = locked.card();
        String text = noteOf(card, note);
        giveUpShortLines(orderId, by);
        Instant at = clock.instant();
        card.executed(by, at);
        ShortageCard saved = cards.save(card);
        orderService.cancel(orderId, new CancelOrderCommand(CancellationReasonCode.OUT_OF_STOCK, text, retainedPercent,
                by, fee, feeBearer));
        shortageCards.announce(saved, orders.findById(new OrderId(orderId)).orElse(null), "EXECUTED", at);
        log.info("Order {}: cancelled on shortage card {} by {}", orderId, cardId, by);
        return shortageCards.card(cardId).orElseThrow();
    }

    /**
     * Reduce the cartons - "Giảm số thùng" (17 §4.14 step 58, D45, D51, D52; 15 §4.6 step 19): drop exactly the missing
     * cartons of the card's batch, no annex, no fee. One transaction: a refusal of any step changes nothing.
     *
     * <ol>
     *   <li>The cartons short now must be the card's: the customer agreed to drop those (step 57 - the card is the
     *       evidence); short of more or fewer since, Sales send a new card (step 60) - refused
     *       {@code SHORTAGE_CARD_STATE_CONFLICT}.</li>
     *   <li>The short pick lines are given up, their holds released ({@link ShortPickLines#cancelShortLines}).</li>
     *   <li>The order's lines lose those cartons and the installments owe less
     *       ({@link OrderServiceImpl#dropShortCartons}); the batch carries fewer once the card is EXECUTED (the schedule
     *       in force reads the card).</li>
     *   <li>The INVENTORY hold is lifted; the order goes on with what it has - to labelling, or, the last batch emptied,
     *       by its shipments (17 §4.8 step 33).</li>
     *   <li>D52, a batch emptied with later ones still to come: the order waits between batches
     *       ({@link OrderServiceImpl#batchEmptied}) and the next is released as usual; either way the open production
     *       orders of that batch are stopped after commit ({@link ShortageBatchEmptied}).</li>
     * </ol>
     *
     * @throws BusinessException {@code SHORTAGE_CARD_NOT_FOUND}, {@code SHORTAGE_CARD_STATE_CONFLICT},
     *         {@code SHORTAGE_OPTION_NOT_AVAILABLE} (also: nothing would be left to deliver and nothing was ever handed
     *         over - no rule of the docs ends an order so), or the inner operation's refusal - nothing changes
     */
    public ShortageCards.Card reduceCartons(UUID orderId, UUID cardId, Long version, String note, UUID by) {
        Locked locked = lock(orderId, cardId, version, ShortageOption.REDUCE_CARTONS);
        requireHeldForStock(locked.order());
        ShortageCard card = locked.card();
        requireSameShortage(orderId, card);
        DeliveryBatch batch = contracts.findLiveByOrderId(orderId).map(SalesContract::schedule).orElse(List.of())
                .stream().filter(b -> b.id().equals(card.deliveryBatchId())).findFirst()
                .orElseThrow(() -> new BusinessException(ErrorCode.SHORTAGE_CARD_STATE_CONFLICT, ("Shortage card %s: "
                        + "its batch is not in the order's schedule").formatted(card.code()))
                        .localized("refusal.shortage.stateConflict"));
        Map<UUID, Integer> units = new LinkedHashMap<>();
        for (ShortageCard.Line line : card.lines()) {
            if (batch.cartonsOf(line.orderLineId()) < line.missingCartons()) {
                throw new BusinessException(ErrorCode.SHORTAGE_CARD_STATE_CONFLICT, ("Shortage card %s: batch %d "
                        + "carries %d carton(s) of %s, not the %d missing").formatted(card.code(), batch.seq(),
                        batch.cartonsOf(line.orderLineId()), line.sku(), line.missingCartons()))
                        .localized("refusal.shortage.shortageChanged");
            }
            units.merge(line.orderLineId(), line.missingCartons() * line.unitsPerCarton(), Integer::sum);
        }
        int carried = batch.lines().stream().mapToInt(DeliveryBatch.Line::cartons).sum();
        boolean emptied = carried == card.missingCartons();
        String text = noteOf(card, note);
        giveUpShortLines(orderId, by);
        orderService.dropShortCartons(orderId, card.id(), card.deliveryBatchId(), units, emptied, text, by);
        orderService.resolveHold(orderId, text, by, null);
        ShortageCards.Card done = executed(card, orderId, by);
        if (emptied) {
            orderService.batchEmptied(orderId, text, by);
            Order order = orders.findById(new OrderId(orderId)).orElseThrow();
            events.publish(new ShortageBatchEmptied(orderId, order.orderNumber().value(), card.deliveryBatchId(),
                    batch.seq(), card.code(), by));
        }
        return done;
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

    /**
     * The cartons short now, per order line of the card's batch, are the card's (17 §4.14 steps 57, 60): what the customer
     * agreed to is what is carried out. Read under the pick tasks' and the order's locks.
     */
    private void requireSameShortage(UUID orderId, ShortageCard card) {
        ShortPickLines warehouse = shortLines.getIfAvailable();
        if (warehouse == null) {
            return;
        }
        Map<UUID, Integer> now = new HashMap<>();
        warehouse.shortLinesOf(orderId).forEach(l -> now.merge(l.orderLineId(), l.missingCartons(), Integer::sum));
        Map<UUID, Integer> carded = new HashMap<>();
        card.lines().forEach(l -> carded.merge(l.orderLineId(), l.missingCartons(), Integer::sum));
        if (!now.equals(carded)) {
            throw new BusinessException(ErrorCode.SHORTAGE_CARD_STATE_CONFLICT, ("Shortage card %s shows %s carton(s) "
                    + "missing per line, the warehouse is short of %s now - send a new card").formatted(card.code(),
                    carded, now)).localized("refusal.shortage.shortageChanged");
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
        String note = texts.getString("shortage.execution.note").formatted(card.code());
        String own = salesNote == null || salesNote.isBlank() ? null : salesNote.strip();
        return own == null ? note : note + ". " + own;
    }
}
