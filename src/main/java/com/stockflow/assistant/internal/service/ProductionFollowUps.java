package com.stockflow.assistant.internal.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stockflow.assistant.internal.domain.BusinessDate;
import com.stockflow.common.api.PageResponse;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.security.Action;
import com.stockflow.common.security.CurrentUser;
import com.stockflow.customer.api.CustomerLabel;
import com.stockflow.customer.api.CustomerService;
import com.stockflow.order.api.ProductionOrderQueries;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * The Production Coordinator's follow-ups (contract 3.3). Producers are outside the system: chasing one is a phone call
 * or a Zalo message, and today the coordinator works out whom to call from the production orders screen and the
 * schedule. This lists the production orders to chase - late, at risk, or due within {@value #DUE_WITHIN_DAYS} days -
 * the most urgent first, each with the delivery batch and customer it holds up; and drafts the message to the producer.
 *
 * <p><b>The list is code</b> ({@link ProductionOrderQueries#followUps}: the delay alerts' rules on today's dates, the
 * screen's warehouse rule). The sales order and its customer are named only to a caller who may read orders
 * ({@code sales-orders:READ}), as the order list names them; to anyone else the batch is its window.</p>
 *
 * <p><b>The draft is the model's</b>, the one place language is the product - and it goes to the producer, so it is
 * built from what the producer needs and nothing else: the production order's code, its SKUs and cartons, what was
 * received, its dates. No customer, no order number, no price: the producer is a supplier, and a customer's name in a
 * message to a supplier is a leak the coordinator would have to notice and delete. The facts go to the model as JSON
 * with the instruction to use only them, written as every draft is ({@link DraftWriter}): the answer's codes and
 * numbers are checked against those facts and whatever no fact holds is returned in {@code unverifiedCodes} for the
 * screen to flag. Nothing is sent: the coordinator copies, edits and sends it (contract §0 rule 1).</p>
 */
@Service
public class ProductionFollowUps {

    private static final java.time.format.DateTimeFormatter DAY =
            java.time.format.DateTimeFormatter.ofPattern("dd/MM/yyyy");

    /** "Due within 3 days" (contract 3.3). */
    static final int DUE_WITHIN_DAYS = 3;

    /** Rows one list returns: a coordinator's day, not a report. */
    static final int LIMIT = ProductionOrderQueries.MAX_ORDERS;

    static final String SYSTEM_PROMPT = """
            You write a short message in Vietnamese from a production coordinator to a printing producer (a supplier), \
            to be sent over Zalo or e-mail after the coordinator reads and edits it. Use only the facts in the JSON the \
            user gives: the production order's code, its SKUs with the cartons ordered and received, and its dates. \
            Do not invent any date, quantity, code or promise; write every code and number exactly as the facts give \
            it. Never mention a customer, a sales order or a price: the facts hold none, and the producer must not get \
            them. Say politely what is late or due (by the reason in the facts), and ask the producer to confirm a \
            completion date and how many cartons are ready. Address the producer by the name in the facts. Write a \
            date as dd/MM/yyyy, as the facts give it. No more than 120 words, plain text, no markdown, no greeting \
            placeholders like [Tên].""";

    private final ProductionOrderQueries productionOrders;
    private final CustomerService customers;
    private final DraftWriter writer;
    private final ObjectMapper json;
    private final Clock clock;

    ProductionFollowUps(ProductionOrderQueries productionOrders, CustomerService customers, DraftWriter writer,
                        ObjectMapper json, Clock clock) {
        this.productionOrders = productionOrders;
        this.customers = customers;
        this.writer = writer;
        this.json = json;
        this.clock = clock;
    }

    /** The follow-up list for {@code user}; run inside the production orders screen's guard. */
    public FollowUpList list(CurrentUser user) {
        LocalDate today = BusinessDate.of(clock.instant());
        PageResponse<ProductionOrderQueries.FollowUp> page = productionOrders.followUps(today, DUE_WITHIN_DAYS, null,
                LIMIT);
        boolean namesOrders = user != null && user.can(ToolPermissions.SALES_ORDERS, Action.READ);
        Map<UUID, CustomerLabel> names = namesOrders ? names(page.items()) : Map.of();
        List<Item> items = page.items().stream().map(followUp -> item(followUp, namesOrders, names)).toList();
        return new FollowUpList(today, items, page.totalElements(), page.totalElements() > items.size());
    }

    private static Item item(ProductionOrderQueries.FollowUp followUp, boolean namesOrders,
                             Map<UUID, CustomerLabel> names) {
        ProductionOrderQueries.ProductionOrderRow order = followUp.order();
        CustomerLabel customer = followUp.customerId() == null ? null : names.get(followUp.customerId());
        Affected affects = new Affected(namesOrders ? order.salesOrderNumber() : null, order.deliveryBatchSeq(),
                customer == null ? null : customer.name(), followUp.batchWindowStart(), followUp.batchWindowEnd());
        return new Item(order.id(), order.code(), order.producerName(), order.status(), followUp.reason().name(),
                order.expectedCompletionDate(), order.needByDate(), followUp.daysLate(), order.cartons(),
                order.receivedCartons(), List.of(affects), priorityOf(followUp.reason()),
                WorkLinks.productionOrder(order.id()));
    }

    /** HIGH: past a day someone gave; MEDIUM: the producer's day is after the need-by day; LOW: due soon. */
    static String priorityOf(ProductionOrderQueries.FollowUpReason reason) {
        return switch (reason) {
            case LATE, PRODUCER_OVERDUE -> "HIGH";
            case AT_RISK -> "MEDIUM";
            case DUE_SOON, NONE -> "LOW";
        };
    }

    private Map<UUID, CustomerLabel> names(List<ProductionOrderQueries.FollowUp> followUps) {
        List<UUID> ids = followUps.stream().map(ProductionOrderQueries.FollowUp::customerId).filter(Objects::nonNull)
                .distinct().toList();
        return ids.isEmpty() ? Map.of() : customers.labels(ids);
    }

    /**
     * The draft message to the producer of one production order the caller may see; run inside the production
     * orders screen's guard.
     *
     * @throws BusinessException {@code PRODUCTION_ORDER_NOT_FOUND} for one that does not exist or is of a warehouse
     *         the caller is not assigned to; {@code PRODUCTION_ORDER_STATUS_INVALID} for one that is not with its
     *         producer (a draft, one received in full, closed or cancelled: there is nobody to chase);
     *         {@code ASSISTANT_UNAVAILABLE} when no model answers
     */
    public Draft draft(UUID productionOrderId) {
        LocalDate today = BusinessDate.of(clock.instant());
        ProductionOrderQueries.FollowUp followUp = productionOrders.followUp(productionOrderId, today)
                .orElseThrow(() -> new BusinessException(ErrorCode.PRODUCTION_ORDER_NOT_FOUND,
                        "Production order " + productionOrderId + " not found"));
        ProductionOrderQueries.ProductionOrderRow order = followUp.order();
        if (!ProductionOrderQueries.WITH_PRODUCER_STATUSES.contains(order.status())) {
            throw new BusinessException(ErrorCode.PRODUCTION_ORDER_STATUS_INVALID, "Production order " + order.code()
                    + " is " + order.status() + ": it is not with its producer, so there is nobody to chase");
        }
        writer.requireModel();
        String facts = facts(followUp, today);
        DraftWriter.Written written = writer.write("producer message", SYSTEM_PROMPT, facts);
        // No customer is named to a producer, so there is no name to hide or to put back.
        return writer.draft(written, List.of(facts), DraftNames.NONE, List.of(new Draft.Source(
                Draft.Kind.PRODUCTION_ORDER, order.code(), WorkLinks.productionOrder(order.id()))));
    }

    /**
     * What the producer may be told, as JSON: never the customer, the sales order or a price. Dates as every draft
     * writes them, {@code dd/MM/yyyy} ({@link DraftWriter#today}): the model is told to write them as given, and the
     * grounding check reads an ISO date's parts as a code's.
     */
    String facts(ProductionOrderQueries.FollowUp followUp, LocalDate today) {
        ProductionOrderQueries.ProductionOrderRow order = followUp.order();
        Map<String, Object> facts = new LinkedHashMap<>();
        facts.put("today", day(today));
        facts.put("producer", order.producerName());
        facts.put("productionOrder", order.code());
        facts.put("status", order.status());
        facts.put("reason", followUp.reason().name());
        facts.put("needByDate", day(order.needByDate()));
        facts.put("expectedCompletionDate", day(order.expectedCompletionDate()));
        facts.put("daysLate", followUp.daysLate());
        facts.put("lines", order.lines().stream().map(line -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("sku", line.sku());
            row.put("cartonsOrdered", line.cartons());
            row.put("unitsPerCarton", line.unitsPerCarton());
            row.put("cartonsReceived", line.receivedCartons());
            return row;
        }).toList());
        if (order.latestSampleRound() != null) {
            facts.put("latestPrintSampleRound", order.latestSampleRound().roundNo());
            facts.put("latestPrintSampleResult", order.latestSampleRound().result());
        }
        try {
            return json.writeValueAsString(facts);
        } catch (JsonProcessingException impossible) {
            throw new IllegalStateException("Facts of a production order could not be written", impossible);
        }
    }

    private static String day(LocalDate day) {
        return day == null ? null : DAY.format(day);
    }

    /**
     * @param total     every production order to chase
     * @param truncated more than {@value #LIMIT}: the rest are on the production orders screen
     */
    public record FollowUpList(LocalDate today, List<Item> items, long total, boolean truncated) {
    }

    /**
     * @param reason   LATE, PRODUCER_OVERDUE, AT_RISK or DUE_SOON
     * @param daysLate past the need-by day (LATE) or the producer's day (PRODUCER_OVERDUE); null otherwise
     * @param priority HIGH, MEDIUM or LOW
     */
    public record Item(UUID productionOrderId, String code, String producer, String status, String reason,
                       LocalDate expectedCompletion, LocalDate neededBy, Integer daysLate, int cartons,
                       int receivedCartons, List<Affected> affects, String priority, String href) {
    }

    /**
     * What a production order holds up: its delivery batch. {@code orderNumber} and {@code customerName} are null for a
     * caller who may not read orders.
     */
    public record Affected(String orderNumber, Integer batchSeq, String customerName, LocalDate windowStart,
                           LocalDate windowEnd) {
    }

}
