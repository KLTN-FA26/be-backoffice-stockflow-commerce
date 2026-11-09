package com.stockflow.order.internal.domain;

import com.stockflow.common.api.FieldError;
import com.stockflow.common.domain.Money;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.id.Identifiers;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * The rules a contract's delivery schedule and payment plan keep against their order (kltn-docs 14 §4.3 steps 11-12,
 * BR-13, BR-23; 15 BR-12, BR-18). Pure: the order is read, never changed. The database checks the same rules when the
 * transaction commits ({@code ordering.check_sales_contract_terms}); here they are checked first, so a refusal names
 * every rule broken, by field, instead of the first one the database met.
 */
public final class ContractTerms {

    private static final BigDecimal HUNDRED = new BigDecimal("100");

    /** A batch as Sales enters it, in delivery order: its window (both days or neither) and its cartons. */
    public record BatchEntry(LocalDate windowStart, LocalDate windowEnd, List<LineEntry> lines) {

        public BatchEntry {
            lines = lines == null ? List.of() : List.copyOf(lines);
        }
    }

    /** Cartons of one order line in a batch. */
    public record LineEntry(UUID orderLineId, Integer cartons) { }

    /**
     * An installment as Sales enters it, in order: an amount or a share of the total with tax - one of the two - and a
     * milestone.
     *
     * @param dueDays          days after the milestone; left out: 0 on signing, the customer's credit term after
     *                         delivery; never for BEFORE_DELIVERY, which has no due day
     * @param deliveryBatchSeq the delivery batch it is tied to (BEFORE_DELIVERY / AFTER_DELIVERY only); null for none
     */
    public record InstallmentEntry(String name, PaymentMilestone milestone, BigDecimal amount, BigDecimal percent,
                                   Integer dueDays, Integer deliveryBatchSeq) { }

    private ContractTerms() {
    }

    /**
     * The schedule a new contract starts from (14 §4.3 step 11): one batch of every carton of the order, its window the
     * day the customer asked for - none when they asked for none, and Sales enters it (BR-19).
     */
    public static List<DeliveryBatch> defaultSchedule(Order order) {
        LocalDate asked = order.lines().stream().map(OrderLine::requestedDeliveryDate).filter(Objects::nonNull)
                .min(LocalDate::compareTo).orElse(null);
        List<DeliveryBatch.Line> lines = order.lines().stream()
                .map(line -> new DeliveryBatch.Line(Identifiers.newId(), line.id(), line.cartons())).toList();
        return List.of(new DeliveryBatch(Identifiers.newId(), 1, asked, asked, lines));
    }

    /**
     * The batches Sales entered, numbered 1..n in the order given (BR-23): at least one; each with at least one carton,
     * of lines of this order, each line once; for every line the cartons over the batches make the line's; a window has
     * both its days, the first no later than the last; a batch starts no earlier than the one before.
     *
     * @throws BusinessException {@code DELIVERY_SCHEDULE_INVALID}, naming every rule broken
     */
    public static List<DeliveryBatch> schedule(Order order, List<BatchEntry> entries) {
        List<FieldError> errors = new ArrayList<>();
        if (entries == null || entries.isEmpty()) {
            throw invalidSchedule(List.of(new FieldError("deliverySchedule", "at least one delivery batch",
                    "BR-23")));
        }
        Map<UUID, OrderLine> lines = new HashMap<>();
        order.lines().forEach(line -> lines.put(line.id(), line));
        Map<UUID, Integer> planned = new HashMap<>();
        List<DeliveryBatch> batches = new ArrayList<>();
        LocalDate previousStart = null;
        for (int i = 0; i < entries.size(); i++) {
            BatchEntry entry = entries.get(i);
            String field = "deliverySchedule[" + i + "]";
            if (entry == null) {
                errors.add(new FieldError(field, "a delivery batch", "NotNull"));
                continue;
            }
            if ((entry.windowStart() == null) != (entry.windowEnd() == null)) {
                errors.add(new FieldError(field + ".windowEnd", "a window has both its days, or neither yet",
                        "BR-23"));
            } else if (entry.windowStart() != null && entry.windowStart().isAfter(entry.windowEnd())) {
                errors.add(new FieldError(field + ".windowEnd", "not before the window's first day", "BR-23"));
            }
            if (entry.windowStart() != null && previousStart != null && entry.windowStart().isBefore(previousStart)) {
                errors.add(new FieldError(field + ".windowStart", "a batch starts no earlier than the one before",
                        "BR-23"));
            }
            if (entry.windowStart() != null) {
                previousStart = entry.windowStart();
            }
            if (entry.lines().isEmpty()) {
                errors.add(new FieldError(field + ".lines", "at least one carton", "BR-23"));
            }
            Set<UUID> seen = new HashSet<>();
            List<DeliveryBatch.Line> batchLines = new ArrayList<>();
            for (int j = 0; j < entry.lines().size(); j++) {
                LineEntry line = entry.lines().get(j);
                String lineField = field + ".lines[" + j + "]";
                if (line == null || line.orderLineId() == null || !lines.containsKey(line.orderLineId())) {
                    errors.add(new FieldError(lineField + ".orderLineId", "a line of order " + order.orderNumber(),
                            "BR-23"));
                    continue;
                }
                if (!seen.add(line.orderLineId())) {
                    errors.add(new FieldError(lineField + ".orderLineId", "each line once in a batch", "BR-23"));
                    continue;
                }
                if (line.cartons() == null || line.cartons() < 1) {
                    errors.add(new FieldError(lineField + ".cartons", "a whole number of cartons, at least 1",
                            "BR-23"));
                    continue;
                }
                planned.merge(line.orderLineId(), line.cartons(), Integer::sum);
                batchLines.add(new DeliveryBatch.Line(Identifiers.newId(), line.orderLineId(), line.cartons()));
            }
            if (errors.isEmpty()) {
                batches.add(new DeliveryBatch(Identifiers.newId(), i + 1, entry.windowStart(), entry.windowEnd(),
                        batchLines));
            }
        }
        for (OrderLine line : order.lines()) {
            int over = planned.getOrDefault(line.id(), 0);
            if (!Objects.equals(line.cartons(), over)) {
                errors.add(new FieldError("deliverySchedule", "%s: %d carton(s) over the batches, the order has %d"
                        .formatted(line.sku().code(), over, line.cartons()), "BR-23"));
            }
        }
        if (!errors.isEmpty()) {
            throw invalidSchedule(errors);
        }
        return List.copyOf(batches);
    }

    /**
     * A contract is sent with every window known and none in the past (BR-23): the customer reads when each batch comes.
     *
     * @throws BusinessException {@code DELIVERY_SCHEDULE_INVALID}
     */
    public static void requireWindows(List<DeliveryBatch> batches, LocalDate today) {
        List<FieldError> errors = new ArrayList<>();
        for (DeliveryBatch batch : batches) {
            String field = "deliverySchedule[" + (batch.seq() - 1) + "]";
            if (!batch.hasWindow()) {
                errors.add(new FieldError(field + ".windowStart", "the delivery window, before the contract is sent",
                        "BR-23"));
            } else if (batch.windowStart().isBefore(today)) {
                errors.add(new FieldError(field + ".windowStart", "not in the past when the contract is sent",
                        "BR-23"));
            }
        }
        if (!errors.isEmpty()) {
            throw invalidSchedule(errors);
        }
    }

    /**
     * The payment plan Sales entered, numbered 1..n in the order given (BR-13, 15 BR-18): at least one installment, each
     * with a name, a milestone and an amount or a share of the order's total with tax; a share is rounded to the dong,
     * and when every installment is a share adding up to 100 the last takes what the rounding left. The amounts add up
     * to the total. Only BEFORE_DELIVERY / AFTER_DELIVERY name a delivery batch, one of the schedule's.
     *
     * @param batches        how many delivery batches the schedule has
     * @param creditApproved whether accounting approved credit for the customer: AFTER_DELIVERY needs it (15 BR-12)
     * @param creditTermDays the customer's credit term, an AFTER_DELIVERY installment's due days when left out
     * @throws BusinessException {@code PAYMENT_PLAN_INVALID}, naming every rule broken;
     *                           {@code PAYMENT_SCHEDULE_NEEDS_CREDIT}
     */
    public static List<PlannedInstallment> plan(Order order, int batches, List<InstallmentEntry> entries,
                                                boolean creditApproved, Integer creditTermDays) {
        Money total = order.total();
        if (entries == null || entries.isEmpty()) {
            throw invalidPlan(List.of(new FieldError("paymentPlan", "at least one installment", "BR-13")));
        }
        List<FieldError> errors = new ArrayList<>();
        List<Money> amounts = new ArrayList<>();
        boolean allShares = true;
        BigDecimal shares = BigDecimal.ZERO;
        boolean afterDelivery = false;
        for (int i = 0; i < entries.size(); i++) {
            InstallmentEntry entry = entries.get(i);
            String field = "paymentPlan[" + i + "]";
            if (entry == null) {
                errors.add(new FieldError(field, "an installment", "NotNull"));
                amounts.add(null);
                continue;
            }
            if (entry.name() == null || entry.name().isBlank() || entry.name().strip().length() > 100) {
                errors.add(new FieldError(field + ".name", "a name of at most 100 characters", "BR-13"));
            }
            if (entry.milestone() == null) {
                errors.add(new FieldError(field + ".milestone", "ON_SIGNING, BEFORE_DELIVERY or AFTER_DELIVERY",
                        "BR-13"));
            }
            afterDelivery |= entry.milestone() == PaymentMilestone.AFTER_DELIVERY;
            if (entry.deliveryBatchSeq() != null) {
                if (entry.milestone() == PaymentMilestone.ON_SIGNING) {
                    errors.add(new FieldError(field + ".deliveryBatchSeq", "an installment on signing is tied to no "
                            + "delivery batch", "BR-13"));
                } else if (entry.deliveryBatchSeq() < 1 || entry.deliveryBatchSeq() > batches) {
                    errors.add(new FieldError(field + ".deliveryBatchSeq", "a delivery batch of the schedule, 1 to "
                            + batches, "BR-13"));
                }
            }
            if (entry.dueDays() != null && (entry.milestone() == PaymentMilestone.BEFORE_DELIVERY
                    || entry.dueDays() < 0 || entry.dueDays() > 365)) {
                errors.add(new FieldError(field + ".dueDays", entry.milestone() == PaymentMilestone.BEFORE_DELIVERY
                        ? "an installment before delivery has no due day: it blocks the handover until paid"
                        : "0 to 365 days", "BR-13"));
            }
            if ((entry.amount() == null) == (entry.percent() == null)) {
                errors.add(new FieldError(field, "an amount or a share of the total, one of the two", "BR-13"));
                amounts.add(null);
                allShares = false;
                continue;
            }
            if (entry.amount() != null) {
                allShares = false;
                if (entry.amount().signum() <= 0 || entry.amount().stripTrailingZeros().scale() > 0) {
                    errors.add(new FieldError(field + ".amount", "whole dong, more than 0", "BR-13"));
                    amounts.add(null);
                } else {
                    amounts.add(new Money(entry.amount(), total.currency()));
                }
            } else if (entry.percent().signum() <= 0 || entry.percent().compareTo(HUNDRED) > 0
                    || entry.percent().stripTrailingZeros().scale() > 2) {
                errors.add(new FieldError(field + ".percent", "more than 0, at most 100, two decimals at most",
                        "BR-13"));
                amounts.add(null);
            } else {
                shares = shares.add(entry.percent());
                amounts.add(new Money(total.amount().multiply(entry.percent())
                        .divide(HUNDRED, 0, RoundingMode.HALF_UP), total.currency()));
            }
        }
        if (afterDelivery && !creditApproved) {
            throw new BusinessException(ErrorCode.PAYMENT_SCHEDULE_NEEDS_CREDIT, ("Order %s: an installment after "
                    + "delivery is credit, and accounting has approved none for this customer").formatted(
                    order.orderNumber()));
        }
        if (errors.isEmpty() && allShares && shares.compareTo(HUNDRED) == 0) {
            // 14 §4.3 step 12: shares rounded to the dong, what is left on the last.
            Money others = amounts.subList(0, amounts.size() - 1).stream().reduce(Money.zero(total.currency()),
                    Money::plus);
            amounts.set(amounts.size() - 1, total.minus(others));
            if (amounts.getLast().amount().signum() <= 0) {
                errors.add(new FieldError("paymentPlan[" + (amounts.size() - 1) + "].percent",
                        "leaves nothing for the last installment", "BR-13"));
            }
        }
        if (errors.isEmpty()) {
            Money planned = amounts.stream().reduce(Money.zero(total.currency()), Money::plus);
            if (!planned.equals(total)) {
                errors.add(new FieldError("paymentPlan", "the installments add up to %s; the order's total with tax "
                        .formatted(planned.amount().toPlainString()) + "is " + total.amount().toPlainString(),
                        "BR-13"));
            }
        }
        if (!errors.isEmpty()) {
            throw invalidPlan(errors);
        }
        List<PlannedInstallment> plan = new ArrayList<>();
        for (int i = 0; i < entries.size(); i++) {
            InstallmentEntry entry = entries.get(i);
            Integer dueDays = entry.milestone() == PaymentMilestone.BEFORE_DELIVERY ? null
                    : entry.dueDays() != null ? entry.dueDays()
                    : entry.milestone() == PaymentMilestone.AFTER_DELIVERY && creditTermDays != null ? creditTermDays
                    : Integer.valueOf(0);
            plan.add(new PlannedInstallment(Identifiers.newId(), i + 1, entry.name().strip(), entry.milestone(),
                    entry.percent(), amounts.get(i), dueDays, entry.deliveryBatchSeq()));
        }
        return List.copyOf(plan);
    }

    /**
     * What each batch's goods are worth with tax, by batch number - the amount an installment tied to it starts from
     * (BR-13): a line's cartons in a batch are a share of the line with tax, cut so that the shares of a line over the
     * batches add up to the line exactly. The fee lines belong to no batch.
     */
    public static Map<Integer, Money> batchValues(Order order, List<DeliveryBatch> batches) {
        Map<Integer, Money> values = new LinkedHashMap<>();
        Map<UUID, Integer> before = new HashMap<>();
        for (DeliveryBatch batch : batches) {
            Money value = Money.zero(order.total().currency());
            for (OrderLine line : order.lines()) {
                int cartons = batch.cartonsOf(line.id());
                if (cartons == 0 || line.unitsPerCarton() == null) {
                    continue;
                }
                int from = before.getOrDefault(line.id(), 0);
                int to = Math.min(line.quantity(), from + cartons * line.unitsPerCarton());
                value = value.plus(line.grossBetween(from, to));
                before.put(line.id(), to);
            }
            values.put(batch.seq(), value);
        }
        return values;
    }

    private static BusinessException invalidSchedule(List<FieldError> errors) {
        return new BusinessException(ErrorCode.DELIVERY_SCHEDULE_INVALID,
                "The delivery schedule breaks " + errors.size() + " rule(s) (14 BR-23)", errors);
    }

    private static BusinessException invalidPlan(List<FieldError> errors) {
        return new BusinessException(ErrorCode.PAYMENT_PLAN_INVALID,
                "The payment plan breaks " + errors.size() + " rule(s) (14 BR-13)", errors);
    }
}
