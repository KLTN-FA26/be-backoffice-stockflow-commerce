package com.stockflow.order.internal.service;

import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.domain.BusinessCalendar;
import com.stockflow.common.id.Identifiers;
import com.stockflow.contracts.ShortageCardChanged;
import com.stockflow.order.api.HoldReason;
import com.stockflow.order.api.OrderStatus;
import com.stockflow.order.api.ShortageCardEnd;
import com.stockflow.order.api.ShortageCards;
import com.stockflow.order.api.ShortageOption;
import com.stockflow.order.internal.domain.DeliveryBatch;
import com.stockflow.order.internal.domain.DeliveryBatchRecords;
import com.stockflow.order.internal.domain.Order;
import com.stockflow.order.internal.domain.OrderId;
import com.stockflow.order.internal.domain.OrderLine;
import com.stockflow.order.internal.domain.OrderRepository;
import com.stockflow.order.internal.domain.ProductionOrder;
import com.stockflow.order.internal.domain.ProductionOrderRepository;
import com.stockflow.order.internal.domain.ProductionScheduleQuery;
import com.stockflow.order.internal.domain.SalesContract;
import com.stockflow.order.internal.domain.SalesContractRepository;
import com.stockflow.order.internal.domain.ShortageCard;
import com.stockflow.order.internal.domain.ShortageCardRepository;
import com.stockflow.order.spi.ShortPickLines;
import com.stockflow.payment.api.OrderPaymentAmounts;
import com.stockflow.payment.api.PaymentService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * The shortage choice card "Đơn thiếu N thùng" (kltn-docs 17 §4.14 steps 55-60, §5.6, BR-33; 16 BR-15): what it may
 * offer, sending it, the customer's one choice, and its ending when the order moves on without it.
 *
 * <p><b>Nothing is typed.</b> The cartons short come from the warehouse ({@link ShortPickLines}), the options from what
 * each would do now, every figure from the operation's own arithmetic: {@link Order#valueOfClosing} is the arithmetic
 * of closing the rest of an order (15 §4.6 step 19), the lead time is {@link ProductionLeadTime}, the same rule
 * {@code PO_NEEDED} uses. Sales type only a note (16 BR-15).</p>
 *
 * <p><b>The options</b> are drawn when the card is sent and frozen on it, so the customer chooses what they were shown
 * (17 §4.14 step 56): wait for production - always, with the expected delivery date (D48); reduce the cartons - always
 * (D45, D51); ship part and close the rest - while the order may still be decided to ship in parts (BR-15) and the batch
 * has a carton to ship; cancel - while no carton is labelled (BR-12). "Reduce" is offered whether or not the batch keeps a
 * carton and whether or not production covers the missing part (owner decision D51, 2026-11-28); carrying it out when
 * the batch is left empty closes that batch (D52, {@link ShortageCardExecutionService#reduceCartons}).</p>
 *
 * <p><b>Locks.</b> The order row first, then the cards ({@code customer_order → shortage_card}); no pick lock is taken here,
 * reading what is short is a plain read. Every end of a card - superseded, expired - is written under the order's lock by
 * the operation that caused it.</p>
 */
@Service
@Transactional
public class ShortageCardService implements ShortageCards {

    private static final Logger log = LoggerFactory.getLogger(ShortageCardService.class);

    /** Why a card cannot be sent, as the staff preview answers it ({@code refusal.shortage.*} in the bundles). */
    public enum NotSendable {
        NOT_ON_HOLD("refusal.shortage.notOnHold"), NOTHING_SHORT("refusal.shortage.nothingShort"),
        LIVE_CARD("refusal.shortage.liveCard"), NO_BATCH("refusal.shortage.noBatch");

        private final String messageKey;

        NotSendable(String messageKey) {
            this.messageKey = messageKey;
        }

        BusinessException refusal(UUID orderId) {
            return new BusinessException(ErrorCode.SHORTAGE_CARD_NOT_SENDABLE,
                    "No shortage card can be sent for order %s now: %s".formatted(orderId, name())).localized(messageKey);
        }
    }

    /** What the system would put on a card for an order now. */
    record Draft(UUID batchId, List<ShortageCard.Line> lines, List<Option> options, LocalDate expectedDate,
                 ShortageCard.DateSource dateSource) {
    }

    /**
     * A card as staff read it: the card and, beside it, who did what - nothing of this reaches the customer.
     *
     * @param expectedDateSource where the wait option's date came from
     */
    public record StaffCard(Card card, UUID sentBy, UUID chosenBy, UUID executedBy,
                            ShortageCard.DateSource expectedDateSource) {
    }

    /**
     * What the order page's "Thiếu thùng" section shows (17 §4.14 step 55).
     *
     * @param sendable      whether a card could be sent now
     * @param notSendable   when it could not, why; null when it could
     * @param batch         the batch the card would be about; null when none is released
     * @param lines         the cartons short; empty when nothing is
     * @param options       what the card would offer, with the figures it would show
     * @param expectedDateSource where the wait option's date would come from; null without a wait option
     * @param live          the live card of the batch, if any
     * @param history       every card of the order, newest first
     */
    public record Preview(UUID orderId, String orderNumber, boolean sendable, NotSendable notSendable, Batch batch,
                          List<Line> lines, List<Option> options, ShortageCard.DateSource expectedDateSource,
                          StaffCard live, List<StaffCard> history) {
    }

    private final OrderRepository orders;
    private final ShortageCardRepository cards;
    private final SalesContractRepository contracts;
    private final DeliveryBatchRecords records;
    private final ProductionOrderRepository productionOrders;
    private final ProductionScheduleQuery schedule;
    private final ProductionLeadTime leadTime;
    private final PaymentService payments;
    private final LineNames names;
    private final OrderEventPublisher events;
    private final Clock clock;
    /** Fulfillment's answer to which cartons are short (order :: spi); absent in a module test. */
    private final ObjectProvider<ShortPickLines> shortLines;

    ShortageCardService(OrderRepository orders, ShortageCardRepository cards, SalesContractRepository contracts,
                        DeliveryBatchRecords records, ProductionOrderRepository productionOrders,
                        ProductionScheduleQuery schedule, ProductionLeadTime leadTime, PaymentService payments,
                        LineNames names, OrderEventPublisher events, Clock clock,
                        ObjectProvider<ShortPickLines> shortLines) {
        this.orders = orders;
        this.cards = cards;
        this.contracts = contracts;
        this.records = records;
        this.productionOrders = productionOrders;
        this.schedule = schedule;
        this.leadTime = leadTime;
        this.payments = payments;
        this.names = names;
        this.events = events;
        this.clock = clock;
        this.shortLines = shortLines;
    }

    // ---- reading ------------------------------------------------------------------------------------------------------

    /**
     * What a card could be sent for this order now and the cards it has had (the back office's section, 17 §4.14 step 55).
     * Read-only: no lock, nothing written.
     *
     * @throws BusinessException {@code NOT_FOUND} for an unknown order
     */
    @Transactional(readOnly = true)
    public Preview preview(UUID orderId) {
        Order order = orders.findById(new OrderId(orderId))
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "No order with id " + orderId));
        List<ShortageCard> history = cards.ofOrder(orderId);
        ShortageCard live = history.stream().filter(c -> c.status().live()).findFirst().orElse(null);
        Draft draft = null;
        NotSendable refusal = null;
        try {
            draft = draw(order);
            if (live != null && live.deliveryBatchId().equals(draft.batchId())) {
                refusal = NotSendable.LIVE_CARD;
            }
        } catch (NotSendableException refused) {
            refusal = refused.why;
        }
        Batch batch = draft == null ? null : batchOf(order, draft.batchId()).orElse(null);
        // The card a refusal like LIVE_CARD names still shows what a new one would offer: Sales may replace it.
        return new Preview(orderId, order.orderNumber().value(), refusal == null, refusal, batch,
                draft == null ? List.of() : draft.lines().stream().map(l -> toLine(l)).toList(),
                draft == null ? List.of() : draft.options(), draft == null ? null : draft.dateSource(),
                live == null ? null : staffView(live, order), history.stream().map(c -> staffView(c, order)).toList());
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Card> card(UUID cardId) {
        if (cardId == null) {
            return Optional.empty();
        }
        return cards.find(cardId).flatMap(c -> orders.findById(new OrderId(c.orderId())).map(o -> view(c, o)));
    }

    /**
     * The customer's live card for one of their orders, as the order page of the portal shows it (17 §5.2).
     *
     * @throws BusinessException {@code NOT_FOUND} for an order that is not the customer's, as for one that does not exist
     */
    @Transactional(readOnly = true)
    public Optional<Card> liveCardOf(UUID customerId, UUID orderId) {
        Order order = ownOrder(customerId, orderId, false);
        return cards.liveOf(orderId).stream().findFirst().map(c -> view(c, order));
    }

    /**
     * The card the customer's order page chooses on: {@code cardId} must be a card of {@code orderId}, which is the
     * customer's. The same operation as the chat's action ({@link #choose}).
     */
    public Card chooseOnOrder(UUID customerId, UUID userId, UUID orderId, UUID cardId, ShortageOption option,
                              String note, Long version) {
        ShortageCard found = cards.find(cardId).filter(c -> c.orderId().equals(orderId))
                .orElseThrow(() -> notFound(cardId));
        return choose(customerId, userId, found.id(), option, note, version);
    }

    // ---- sending ------------------------------------------------------------------------------------------------------

    @Override
    public Card send(UUID customerId, UUID orderId, String note, boolean replaceLive, UUID sentBy) {
        Order order = ownOrder(customerId, orderId, true);
        Draft draft;
        try {
            draft = draw(order);
        } catch (NotSendableException refused) {
            throw refused.why.refusal(orderId);
        }
        Instant now = clock.instant();
        Optional<ShortageCard> live = cards.liveOf(orderId, draft.batchId());
        if (live.isPresent() && !replaceLive) {
            throw NotSendable.LIVE_CARD.refusal(orderId);
        }
        ShortageCard fresh = ShortageCard.send(Identifiers.newId(),
                cards.nextCode(BusinessCalendar.date(now)), orderId, draft.batchId(), order.customerId(),
                draft.lines(), draft.options(), draft.expectedDate(), draft.dateSource(), note, sentBy, now);
        if (live.isPresent()) {
            // One live card per batch: the old one goes first (its link to the new one is checked at commit).
            ShortageCard old = live.get();
            old.supersede(fresh.id());
            cards.save(old);
            announce(old, order, "SUPERSEDED", now);
        }
        ShortageCard saved = cards.save(fresh);
        announce(saved, order, "SENT", now);
        log.info("Order {}: shortage card {} sent for batch {} ({} cartons short)", order.orderNumber(), saved.id(),
                draft.batchId(), saved.missingCartons());
        return view(saved, order);
    }

    @Override
    public void linkMessage(UUID cardId, UUID messageId) {
        cards.linkMessage(cardId, messageId);
    }

    // ---- choosing -----------------------------------------------------------------------------------------------------

    @Override
    public Card choose(UUID customerId, UUID userId, UUID cardId, ShortageOption option, String note, Long version) {
        ShortageCard found = cards.find(cardId).filter(c -> c.customerId().equals(customerId))
                .orElseThrow(() -> notFound(cardId));
        if (option == null) {
            // After the card's owner is known: another customer's card is 404 whatever the body says.
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "Name one of the options on the card");
        }
        Order order = orders.findByIdForUpdate(new OrderId(found.orderId())).orElseThrow(() -> notFound(cardId));
        ShortageCard card = cards.findForUpdate(cardId).orElseThrow(() -> notFound(cardId));
        if (version != null && version != card.version()) {
            throw new BusinessException(ErrorCode.SHORTAGE_CARD_STATE_CONFLICT,
                    "Shortage card %s is at version %d, not %d".formatted(cardId, card.version(), version))
                    .localized("refusal.shortage.stateConflict");
        }
        Instant now = clock.instant();
        card.choose(option, userId, note, now);
        ShortageCard saved = cards.save(card);
        announce(saved, order, "CHOSEN", now);
        log.info("Order {}: customer chose {} on shortage card {}", order.orderNumber(), option, cardId);
        return view(saved, order);
    }

    // ---- ending -------------------------------------------------------------------------------------------------------

    /**
     * The order moved on without the card: every live card of it ends (17 §4.14 step 60). Called by the operations that
     * move it - lifting the hold, cancelling, closing the rest - in their transaction and under the order's lock, so no
     * choice races it. Cheap when the order has no live card; idempotent.
     *
     * @return how many cards ended
     */
    public int expireLive(UUID orderId, ShortageCardEnd reason) {
        List<ShortageCard> live = cards.liveForUpdate(orderId);
        if (live.isEmpty()) {
            return 0;
        }
        Instant now = clock.instant();
        Order order = orders.findById(new OrderId(orderId)).orElse(null);
        int ended = 0;
        for (ShortageCard card : live) {
            if (card.expire(reason)) {
                announce(cards.save(card), order, "EXPIRED", now);
                ended++;
            }
        }
        return ended;
    }

    /**
     * The rest of the order was closed (17 §4.8 step 33), by whichever route: a live card whose "ship part, close the
     * rest" Sales started is carried out by it - {@code EXECUTED} by {@code by}, not expired (17 §4.14 step 58: the card
     * is executed when the last step of its option is done, also when Sales closed the rest with the order's own button);
     * every other live card ends as {@code REMAINDER_CLOSED} (step 60). Under the order's lock, in the closure's
     * transaction.
     */
    public void remainderClosed(UUID orderId, UUID by) {
        List<ShortageCard> live = cards.liveForUpdate(orderId);
        if (live.isEmpty()) {
            return;
        }
        Instant now = clock.instant();
        Order order = orders.findById(new OrderId(orderId)).orElse(null);
        for (ShortageCard card : live) {
            if (card.chosenOption() == ShortageOption.SHIP_PART_CLOSE_REST && card.executionStartedAt() != null
                    && by != null) {
                card.executed(by, now);
                announce(cards.save(card), order, "EXECUTED", now);
                log.info("Shortage card {} executed: the rest of order {} was closed", card.id(), orderId);
            } else if (card.expire(ShortageCardEnd.REMAINDER_CLOSED)) {
                announce(cards.save(card), order, "EXPIRED", now);
            }
        }
    }

    /**
     * The warehouse completed the partial check of an order whose live card's "ship part, close the rest" Sales started
     * (17 §4.14 step 58): whoever looks after the customer is told to close the rest now ({@code CLOSE_REST_DUE}). Called
     * by the order's own step that records the partial check, under its lock; nothing for an order without such a card.
     */
    public void partialCheckCompleted(UUID orderId) {
        List<ShortageCard> started = cards.liveOf(orderId).stream()
                .filter(c -> c.chosenOption() == ShortageOption.SHIP_PART_CLOSE_REST && c.executionStartedAt() != null)
                .toList();
        if (started.isEmpty()) {
            return;
        }
        Instant now = clock.instant();
        Order order = orders.findById(new OrderId(orderId)).orElse(null);
        started.forEach(card -> announce(card, order, "CLOSE_REST_DUE", now));
    }

    // ---- drawing the card ---------------------------------------------------------------------------------------------

    /** Thrown inside the service to carry the reason up to {@link #preview} or {@link #send}. */
    private static final class NotSendableException extends RuntimeException {
        @java.io.Serial
        private static final long serialVersionUID = 1L;
        private final transient NotSendable why;

        NotSendableException(NotSendable why) {
            super(why.name(), null, false, false);
            this.why = why;
        }
    }

    /**
     * What the system would put on a card for {@code order} now (17 §4.14 steps 55-56): the batch it was last released
     * for, the cartons short in it, and the options with their figures.
     *
     * @throws NotSendableException when the order is not held for a missing carton, has no released batch or nothing is
     *         short
     */
    private Draft draw(Order order) {
        UUID orderId = order.id().value();
        if (order.status() != OrderStatus.ON_HOLD || order.holdReason() != HoldReason.INVENTORY) {
            throw new NotSendableException(NotSendable.NOT_ON_HOLD);
        }
        List<DeliveryBatchRecords.Release> releases = records.releasesOf(orderId);
        if (releases.isEmpty()) {
            throw new NotSendableException(NotSendable.NO_BATCH);
        }
        UUID batchId = releases.getLast().batchId();
        DeliveryBatch batch = contracts.findLiveByOrderId(orderId).map(SalesContract::schedule).orElse(List.of())
                .stream().filter(b -> b.id().equals(batchId)).findFirst()
                .orElseThrow(() -> new NotSendableException(NotSendable.NO_BATCH));
        ShortPickLines warehouse = shortLines.getIfAvailable();
        List<ShortPickLines.ShortLine> missingLines = warehouse == null ? List.of() : warehouse.shortLinesOf(orderId);
        Map<UUID, OrderLine> orderLines = new HashMap<>();
        order.lines().forEach(l -> orderLines.put(l.id(), l));
        Map<String, LineNames.Names> skuNames = names.of(missingLines.stream().map(ShortPickLines.ShortLine::sku).toList());
        List<ShortageCard.Line> lines = new ArrayList<>();
        for (ShortPickLines.ShortLine missing : missingLines) {
            OrderLine line = orderLines.get(missing.orderLineId());
            int inBatch = batch.cartonsOf(missing.orderLineId());
            int lacks = Math.min(missing.missingCartons(), inBatch);
            if (line == null || lacks <= 0 || line.unitsPerCarton() == null) {
                continue;
            }
            LineNames.Names n = skuNames.getOrDefault(missing.sku(), LineNames.Names.NONE);
            String name = n.variantName() != null ? n.variantName() : n.productName();
            lines.add(new ShortageCard.Line(Identifiers.newId(), missing.orderLineId(), missing.sku(), name, inBatch,
                    inBatch - lacks, lacks, line.unitsPerCarton()));
        }
        if (lines.isEmpty()) {
            throw new NotSendableException(NotSendable.NOTHING_SHORT);
        }
        LocalDate today = BusinessCalendar.date(clock.instant());
        List<ProductionOrder> production = productionOrders.ofSalesOrder(orderId);
        java.util.Set<UUID> shortOrderLines = new java.util.HashSet<>();
        lines.forEach(l -> shortOrderLines.add(l.orderLineId()));
        Expected expected = expectedDelivery(shortOrderLines, batchId, production,
                schedule.expectedCompletionDates(orderId), today, leadTime.leadDays(production));
        DeliveryBatchRecords.Window window = records.windowInForce(orderId, batch);
        List<Option> options = new ArrayList<>();
        boolean afterWindow = window.end() != null && expected.date().isAfter(window.end());
        options.add(Option.waiting(expected.date(), afterWindow));
        options.add(Option.reduce(order.valueOfClosing(missingUnits(lines))));
        Option shipPart = shipPart(order, batch, lines);
        if (shipPart != null) {
            options.add(shipPart);
        }
        if (!order.packedForGood() && order.status().canTransitionTo(OrderStatus.CANCELLED)) {
            options.add(Option.cancel(collected(orderId), order.printingStartedAt() != null));
        }
        return new Draft(batchId, List.copyOf(lines), List.copyOf(options), expected.date(), expected.source());
    }

    /** The wait option's expected delivery date and where it came from. */
    record Expected(LocalDate date, ShortageCard.DateSource source) {
    }

    /**
     * The wait option's expected delivery date (17 §4.14 step 56, D48): the latest expected completion of an open
     * production order of this order and batch that makes one of the SKUs short - if one has that date - else the day the
     * card is sent plus the standard lead time ({@link ProductionLeadTime}, the rule {@code PO_NEEDED} uses).
     *
     * @param shortOrderLines the order lines the card is short of
     * @param production      every production order of the order
     * @param expectedDates   the expected completion day of each production order that has one
     * @param leadDays        the standard lead time to count from {@code today} without such a production order
     */
    static Expected expectedDelivery(java.util.Set<UUID> shortOrderLines, UUID batchId, List<ProductionOrder> production,
                                     Map<UUID, LocalDate> expectedDates, LocalDate today, int leadDays) {
        Optional<LocalDate> covering = production.stream()
                .filter(p -> p.status().open() && p.deliveryBatchId().equals(batchId)
                        && p.lines().stream().anyMatch(l -> shortOrderLines.contains(l.orderLineId())))
                .map(p -> expectedDates.get(p.id())).filter(java.util.Objects::nonNull).max(Comparator.naturalOrder());
        return covering.map(d -> new Expected(d, ShortageCard.DateSource.PRODUCTION_ORDER))
                .orElseGet(() -> new Expected(today.plusDays(leadDays), ShortageCard.DateSource.STANDARD_LEAD_TIME));
    }

    /** Per order line, the units of the cartons that are missing. */
    private static Map<UUID, Integer> missingUnits(List<ShortageCard.Line> lines) {
        Map<UUID, Integer> units = new LinkedHashMap<>();
        lines.forEach(l -> units.merge(l.orderLineId(), l.missingCartons() * l.unitsPerCarton(), Integer::sum));
        return units;
    }

    /**
     * "Ship part, close the rest" (17 §4.14 step 56): offered while the order may still be decided to ship in parts
     * ({@link Order#mayDecidePartialShipment}, BR-15) and the batch keeps a carton to ship. Its figure is what closing the
     * rest would take off once the batch's cartons that are there are shipped: {@link Order#closingUnits} with those
     * kept, valued as every closure is - the missing cartons and every later batch, with VAT.
     *
     * @return null when it is not offered
     */
    private Option shipPart(Order order, DeliveryBatch batch, List<ShortageCard.Line> lines) {
        int batchCartons = batch.lines().stream().mapToInt(DeliveryBatch.Line::cartons).sum();
        int missing = lines.stream().mapToInt(ShortageCard.Line::missingCartons).sum();
        if (!order.mayDecidePartialShipment() || batchCartons - missing < 1) {
            return null;
        }
        Map<UUID, OrderLine> byId = new HashMap<>();
        order.lines().forEach(l -> byId.put(l.id(), l));
        Map<UUID, Integer> missingByLine = missingUnits(lines);
        Map<UUID, Integer> kept = new HashMap<>();
        for (DeliveryBatch.Line line : batch.lines()) {
            OrderLine orderLine = byId.get(line.orderLineId());
            if (orderLine == null || orderLine.unitsPerCarton() == null) {
                continue;
            }
            int units = line.cartons() * orderLine.unitsPerCarton() - missingByLine.getOrDefault(line.orderLineId(), 0);
            kept.merge(line.orderLineId(), Math.max(0, units), Integer::sum);
        }
        BigDecimal reduced = order.valueOfClosing(order.closingUnits(kept));
        List<Integer> later = contracts.findLiveByOrderId(order.id().value()).map(SalesContract::schedule)
                .orElse(List.of()).stream().filter(b -> b.seq() > batch.seq() && !b.lines().isEmpty())
                .map(DeliveryBatch::seq).sorted().toList();
        return Option.shipPart(reduced, later);
    }

    /** What the order's installments have collected: what a cancellation with no fee refunds (15 §4.6 step 18). */
    private BigDecimal collected(UUID orderId) {
        OrderPaymentAmounts amounts = payments.paymentAmounts(List.of(orderId)).get(orderId);
        return amounts == null ? BigDecimal.ZERO : amounts.paid();
    }

    // ---- views and events ---------------------------------------------------------------------------------------------

    private Order ownOrder(UUID customerId, UUID orderId, boolean forUpdate) {
        Optional<Order> found = forUpdate ? orders.findByIdForUpdate(new OrderId(orderId))
                : orders.findById(new OrderId(orderId));
        return found.filter(o -> customerId != null && customerId.equals(o.customerId()))
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "No order with id " + orderId));
    }

    private static BusinessException notFound(UUID cardId) {
        return new BusinessException(ErrorCode.SHORTAGE_CARD_NOT_FOUND, "No shortage card " + cardId);
    }

    private Optional<Batch> batchOf(Order order, UUID batchId) {
        UUID orderId = order.id().value();
        return contracts.findLiveByOrderId(orderId).map(SalesContract::schedule).orElse(List.of()).stream()
                .filter(b -> b.id().equals(batchId)).findFirst().map(b -> {
                    DeliveryBatchRecords.Window window = records.windowInForce(orderId, b);
                    boolean moved = records.windowChangesOf(orderId).stream().anyMatch(c -> c.batchId().equals(batchId));
                    return new Batch(b.seq(), window.start(), window.end(), moved);
                });
    }

    private Card view(ShortageCard c, Order order) {
        Batch batch = batchOf(order, c.deliveryBatchId()).orElse(new Batch(0, null, null, false));
        return new Card(c.id(), c.code(), c.orderId(), order.orderNumber().value(), c.customerId(), c.status(), c.version(), batch,
                c.missingCartons(), c.lines().stream().map(ShortageCardService::toLine).toList(), c.options(),
                c.salesNote(), c.sentAt(),
                c.chosenOption() == null ? null : new Chosen(c.chosenOption(), c.chosenAt(), c.customerNote()),
                c.executedAt(), c.executionStartedAt() != null, c.endedReason(), c.chatMessageId());
    }

    private StaffCard staffView(ShortageCard c, Order order) {
        return new StaffCard(view(c, order), c.sentBy(), c.chosenBy(), c.executedBy(), c.expectedDateSource());
    }

    private static Line toLine(ShortageCard.Line l) {
        return new Line(l.sku(), l.skuName(), l.batchCartons(), l.availableCartons(), l.missingCartons(),
                l.unitsPerCarton());
    }

    /** Tells the chat and the notices (after commit): the card changed. {@code order} may be null (not found). */
    void announce(ShortageCard c, Order order, String change, Instant now) {
        events.publish(new ShortageCardChanged(c.id(), c.orderId(), order == null ? null : order.orderNumber().value(),
                c.customerId(), c.status().name(), c.chosenOption() == null ? null : c.chosenOption().name(),
                c.missingCartons(), c.executionStartedAt() != null, change, now,
                order == null ? null : order.shippingWarehouseId()));
    }
}
