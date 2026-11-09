package com.stockflow.order.internal.domain;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Which delay alerts hold for one sales order on one day, and how the open ones change (4A-7, kltn-docs 17 §4.12 step
 * 48, BR-30). Pure: the facts are read by the caller, so every rule is a unit test away.
 *
 * <ul>
 *   <li><b>AT_RISK</b> - a production order the producer accepted and is still making ({@link #AT_RISK_STATUSES}:
 *       ACCEPTED onward, not yet received in full) has an expected completion day after its need-by day. The day is
 *       given when the producer accepts (17 §4.3 step 8), so a SENT order has none to compare.</li>
 *   <li><b>PRODUCTION_LATE</b> - the need-by day is past (today is after it) and the production order is sent but not
 *       received in full ({@link #LATE_STATUSES}: SENT to PARTIALLY_RECEIVED). A draft or one waiting for approval is
 *       not with a producer, so it cannot be late; one received, closed or cancelled no longer brings anything.</li>
 *   <li><b>PO_NEEDED</b> - an order whose shortage is followed ({@link Facts#shortageFollowed}) has a batch with a
 *       window that still lacks cartons no open production order brings (the production order's own count of what a
 *       batch lacks - held stock first, then what open production orders bring, the earliest batch first), and the
 *       window starts within the lead time: {@code windowStart - today <= leadDays} (B44). A batch with no window yet
 *       has no day to count to; a batch handed over needs nothing made, whatever its count says.</li>
 *   <li><b>DELIVERY_LATE</b> - an order whose delivery is followed ({@link Facts#deliveryFollowed}) has a batch whose
 *       window ended before today and which is not all handed over ({@link #handedOver}).</li>
 * </ul>
 *
 * <p>The two alerts of a production order are independent: one late and at risk shows both, as the table of step 48
 * lists them. Nothing here blocks anything (step 49): the result only opens and closes alerts.</p>
 */
public final class DelayAlertEvaluator {

    /** 17 §4.12: "Đơn sản xuất {@code Accepted} trở đi" that is still open. */
    public static final Set<ProductionOrderStatus> AT_RISK_STATUSES = EnumSet.of(ProductionOrderStatus.ACCEPTED,
            ProductionOrderStatus.SAMPLE_PENDING, ProductionOrderStatus.IN_PRODUCTION,
            ProductionOrderStatus.PARTIALLY_RECEIVED);

    /** 17 §4.12: "chưa {@code Received} (còn {@code Sent} → {@code Partially Received})". */
    public static final Set<ProductionOrderStatus> LATE_STATUSES = EnumSet.of(ProductionOrderStatus.SENT,
            ProductionOrderStatus.ACCEPTED, ProductionOrderStatus.SAMPLE_PENDING, ProductionOrderStatus.IN_PRODUCTION,
            ProductionOrderStatus.PARTIALLY_RECEIVED);

    /** A condition that holds: an alert of {@code type} should be open on {@code subjectId}. */
    public record Condition(DelayAlertType type, UUID subjectId) {

        public Condition {
            Objects.requireNonNull(type, "type");
            Objects.requireNonNull(subjectId, "subjectId");
        }
    }

    /** A production order of the sales order, as far as delay goes. */
    public record Production(UUID id, ProductionOrderStatus status, LocalDate needByDate,
                             LocalDate expectedCompletionDate) {

        public Production {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(status, "status");
            Objects.requireNonNull(needByDate, "needByDate");
        }
    }

    /**
     * A delivery batch of the sales order's contract.
     *
     * @param uncoveredCartons what it still lacks once held stock and open production orders are counted
     * @param handedOver       every carton of it handed over (or closed)
     */
    public record Batch(UUID id, LocalDate windowStart, LocalDate windowEnd, int uncoveredCartons,
                        boolean handedOver) {

        public Batch {
            Objects.requireNonNull(id, "id");
            if ((windowStart == null) != (windowEnd == null)) {
                throw new IllegalArgumentException("A delivery window has both its days or neither");
            }
            if (uncoveredCartons < 0) {
                throw new IllegalArgumentException("A batch lacks no fewer than no cartons");
            }
        }
    }

    /**
     * Everything the alerts of one sales order depend on.
     *
     * @param shortageFollowed whether the order is at the stage where its shortage turns into production orders
     *                         (confirmed, waiting for stock): before it, nothing is held yet; after release, what is
     *                         consumed no longer counts as held
     * @param deliveryFollowed whether the order still has goods to hand over (confirmed up to partly fulfilled)
     * @param leadDays         the standard lead time PO_NEEDED counts with (B44)
     */
    public record Facts(List<Production> productions, List<Batch> batches, boolean shortageFollowed,
                        boolean deliveryFollowed, int leadDays) {

        public Facts {
            productions = List.copyOf(productions);
            batches = List.copyOf(batches);
            if (leadDays < 0) {
                throw new IllegalArgumentException("A lead time is not negative");
            }
        }
    }

    /** What to do with the open alerts: conditions to open an alert for, and alerts to close. */
    public record Changes(List<Condition> toOpen, List<DelayAlert> toClose) {

        public boolean none() {
            return toOpen.isEmpty() && toClose.isEmpty();
        }
    }

    private DelayAlertEvaluator() {
    }

    /** The conditions that hold for the order's facts on {@code today} (the business day). */
    public static Set<Condition> conditions(Facts facts, LocalDate today) {
        Objects.requireNonNull(today, "today");
        Set<Condition> holding = new LinkedHashSet<>();
        for (Production production : facts.productions()) {
            if (AT_RISK_STATUSES.contains(production.status()) && production.expectedCompletionDate() != null
                    && production.expectedCompletionDate().isAfter(production.needByDate())) {
                holding.add(new Condition(DelayAlertType.AT_RISK, production.id()));
            }
            if (LATE_STATUSES.contains(production.status()) && today.isAfter(production.needByDate())) {
                holding.add(new Condition(DelayAlertType.PRODUCTION_LATE, production.id()));
            }
        }
        for (Batch batch : facts.batches()) {
            if (batch.windowStart() == null) {
                continue;
            }
            if (facts.shortageFollowed() && !batch.handedOver() && batch.uncoveredCartons() > 0
                    && ChronoUnit.DAYS.between(today, batch.windowStart()) <= facts.leadDays()) {
                holding.add(new Condition(DelayAlertType.PO_NEEDED, batch.id()));
            }
            if (facts.deliveryFollowed() && !batch.handedOver() && today.isAfter(batch.windowEnd())) {
                holding.add(new Condition(DelayAlertType.DELIVERY_LATE, batch.id()));
            }
        }
        return holding;
    }

    /**
     * Compares the open alerts of the order with the conditions that hold: an alert stays open while its condition
     * holds and is closed once it does not (step 49: "tự đóng khi điều kiện không còn"); a condition without an open
     * alert opens one. Closed alerts passed in are ignored.
     */
    public static Changes reconcile(Collection<DelayAlert> open, Set<Condition> holding) {
        Map<Condition, DelayAlert> current = new HashMap<>();
        List<DelayAlert> toClose = new ArrayList<>();
        for (DelayAlert alert : open) {
            if (!alert.isOpen()) {
                continue;
            }
            Condition condition = new Condition(alert.type(), alert.subjectId());
            if (holding.contains(condition) && current.putIfAbsent(condition, alert) == null) {
                continue;
            }
            toClose.add(alert);
        }
        List<Condition> toOpen = holding.stream().filter(condition -> !current.containsKey(condition)).toList();
        return new Changes(toOpen, toClose);
    }

    /**
     * The batches of {@code schedule} every carton of which is handed over or closed. What left counts for the earliest
     * batch first, as held stock does (17 §4.2 step 3, BR-29: batches are released and handed over in their order): a
     * batch is done when, for each of its lines, the cartons settled reach that line's cartons in it and in every
     * batch before it.
     *
     * @param settledCartons per order line, the cartons handed over (and not come back) or closed
     */
    public static Set<UUID> handedOver(List<DeliveryBatch> schedule, Map<UUID, Integer> settledCartons) {
        Set<UUID> done = new LinkedHashSet<>();
        Map<UUID, Integer> through = new HashMap<>();
        for (DeliveryBatch batch : schedule.stream().sorted(Comparator.comparingInt(DeliveryBatch::seq)).toList()) {
            boolean all = true;
            for (DeliveryBatch.Line line : batch.lines()) {
                int upTo = through.merge(line.orderLineId(), line.cartons(), Integer::sum);
                if (settledCartons.getOrDefault(line.orderLineId(), 0) < upTo) {
                    all = false;
                }
            }
            if (all) {
                done.add(batch.id());
            }
        }
        return done;
    }
}
