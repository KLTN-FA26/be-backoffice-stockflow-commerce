package com.stockflow.assistant.internal.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import com.fasterxml.jackson.databind.cfg.JsonNodeFeature;
import com.fasterxml.jackson.databind.node.NullNode;
import com.stockflow.assistant.internal.domain.ToolCall;
import com.stockflow.assistant.internal.domain.ToolChannel;
import com.stockflow.assistant.internal.domain.ToolExecution;
import com.stockflow.assistant.internal.domain.ToolOutcome;
import com.stockflow.common.i18n.Messages;
import com.stockflow.common.security.CurrentUser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * The "today" card (design §9, FEAT-7): what wants attention in the caller's own work, read when they open the panel.
 * It replaces the 06:00 briefing first planned, which would have run as the system and so either shown a warehouse
 * manager the company-wide figures their screens hide, or needed a warehouse argument in three modules' {@code api}.
 *
 * <p><b>The chat's tools, run the way a question runs them.</b> Each section is one fixed call of a tool through
 * {@link AssistantTools#execute}: the tool's guard, data scope, warehouse rule and masking are exactly a question's,
 * so the card cannot show what asking would not. A section appears only when {@link ToolAvailability} grants its tool.
 * Under the seeded grants that keeps company-wide low stock from a manager limited to warehouses, and held orders from
 * anyone who may not read orders, as their screens do (§13 AQ-15).</p>
 *
 * <p><b>No model, no question.</b> Nothing is sent to a model and no question is stored, so the card costs nothing of
 * the daily question limit. What it costs is its tools' warehouse queries: the endpoint allows one card a minute per
 * user.</p>
 *
 * <p><b>One failure is one section.</b> A tool refused or failing when run gives its section that outcome, no table
 * and a note; the other sections are unaffected. An answer the card cannot read is FAILED as well: it never shows a
 * list it cannot count.</p>
 *
 * <p><b>Nothing is kept.</b> No stored briefing and no cache: like an earlier question's rows, which {@code History}
 * never sends again, the card is read anew under the grants the user holds at that moment. Its tool runs leave no
 * {@code assistant.tool_call} rows, which belong to a conversation and a model turn; each card is logged with its
 * tools, outcomes and timings instead.</p>
 *
 * <p><b>Tables are an answer's tables</b> ({@link ResultTable.Table}, contract C3), keyed by their section: the columns
 * are field names of the tool's own row, a nested field as {@code hold.reason}; every row carries every column, null
 * where the tool left the field out; numbers stay as the tool wrote them, so an amount reads {@code 12960000.00} and
 * never {@code 1.296E7}. Which fields a section shows is the card's choice: one that is the same on every row of it
 * (the status of the held orders) is left out. The rows are built here, not by {@link ResultTable#of}: one answer of
 * {@code findExpiringLots} fills two sections, and the late purchase orders merge two reads.</p>
 */
@Service
public class TodayCard {

    private static final Logger log = LoggerFactory.getLogger(TodayCard.class);

    /** Rows a section lists; its total counts every match. A card to glance at, not a report. */
    static final int ROWS = 10;

    /** How far ahead the expiring lots look: two weeks leaves time to sell, use or move a lot before it expires. */
    static final int EXPIRY_DAYS = 14;

    /**
     * Purchase orders read in each status a supplier can be late in. {@code findPurchaseOrders} has no overdue filter;
     * in these statuses it lists the earliest expected first, so the card reads this many and keeps the overdue ones,
     * and misses one only when more than this many of a status are late. Twenty rows of the longest kind (a
     * 200-character supplier name and a refusal reason on each) still fit {@link ToolExecution#MAX_RESULT_LENGTH};
     * fifty would come back RESULT_TOO_LARGE.
     */
    static final int PURCHASE_ORDERS_READ = 20;

    /** Sent to the supplier: the statuses in which {@code findPurchaseOrders} judges an order overdue. */
    static final List<String> SENT_STATUSES = List.of("CONFIRMED", "PARTIALLY_RECEIVED");

    static final String LOW_STOCK = "lowStock";
    static final String EXPIRED_LOTS = "expiredLots";
    static final String EXPIRING_LOTS = "expiringLots";
    static final String HELD_ORDERS = "heldOrders";
    static final String AWAITING_PAYMENT = "awaitingPayment";
    static final String OVERDUE_PURCHASE_ORDERS = "overduePurchaseOrders";
    static final String OVERDUE_PRODUCTION = "overdueProduction";

    private static final String FIND_LOW_STOCK = "findLowStock";
    private static final String FIND_EXPIRING_LOTS = "findExpiringLots";
    private static final String FIND_ORDERS = "findOrders";
    private static final String FIND_PURCHASE_ORDERS = "findPurchaseOrders";
    private static final String FIND_PRODUCTION_ORDERS = "findProductionOrders";

    static final String LOW_STOCK_ARGUMENTS = "{\"limit\":%d}".formatted(ROWS);
    static final String EXPIRING_LOTS_ARGUMENTS = "{\"withinDays\":%d,\"limit\":%d}".formatted(EXPIRY_DAYS, ROWS);
    static final String OVERDUE_PRODUCTION_ARGUMENTS = """
            {"productionOrderNumber":"","salesOrderNumber":"","status":"OPEN","due":"OVERDUE","limit":%d}"""
            .formatted(ROWS);

    private static final Listing LOW_STOCK_ROWS = new Listing("skus", "total", "truncated", List.of("sku",
            "usableOnHand", "availableToPromise", "reorderPoint", "safetyStock", "atOrBelowReorderPoint",
            "belowSafetyStock"));
    /** {@code stillHeld}: an order or production still holds the expired lot, and must take other stock first. */
    private static final Listing EXPIRED_ROWS = new Listing("expired", "expiredTotal", "expiredTruncated",
            List.of("sku", "warehouseCode", "lotNumber", "expiryDate", "daysToExpiry", "quantityAtLastCheck",
                    "lastCheckedAt", "stillHeld"));
    private static final Listing EXPIRING_ROWS = new Listing("expiring", "expiringTotal", "expiringTruncated",
            List.of("sku", "warehouseCode", "lotNumber", "expiryDate", "daysToExpiry", "quantityAtLastCheck",
                    "lastCheckedAt"));
    private static final Listing HELD_ROWS = new Listing("orders", "total", "truncated", List.of("orderNumber",
            "placedAt", "total", "currency", "paymentTerm", "paymentStatus", "hold.reason", "hold.note",
            "hold.statusBeforeHold"));
    /** paidAmount and depositRequired: what the order still waits for, which its total alone does not say. */
    private static final Listing AWAITING_PAYMENT_ROWS = new Listing("orders", "total", "truncated",
            List.of("orderNumber", "placedAt", "total", "currency", "paymentTerm", "paymentStatus", "paidAmount",
                    "depositRequired"));
    private static final List<String> OVERDUE_PURCHASE_ORDER_COLUMNS = List.of("poNumber", "type", "supplierCode",
            "supplierName", "warehouseCode", "status", "supplierConfirmationStatus", "orderDate", "expectedAt",
            "daysLate", "totalIncludingTax", "currency");
    private static final Listing OVERDUE_PRODUCTION_ROWS = new Listing("productionOrders", "total", "truncated",
            List.of("productionOrderNumber", "type", "mode", "status", "holdReason", "warehouseCode", "blankSku",
                    "plannedUnits", "goodUnitsPrinted", "dueDate", "salesOrderNumber", "sampleRequestNumber",
                    "sampleRound"));

    /** The most days late first; the PO number keeps equal ones in the same order from one card to the next. */
    private static final Comparator<JsonNode> MOST_LATE_FIRST = Comparator
            .comparingLong((JsonNode order) -> order.path("daysLate").asLong(0)).reversed()
            .thenComparing(order -> order.path("poNumber").asText(""));

    private final ParallelTools tools;
    private final ToolAvailability availability;
    private final Messages messages;
    private final Clock clock;
    private final ToolRunRecorder recorder;

    /** Numbers as the tool wrote them: read as doubles, an amount of 12960000.00 would be written back as 1.296E7. */
    private final ObjectReader reader;

    /** @param tools runs the card's reads at once: none depends on another */
    TodayCard(ParallelTools tools, ToolAvailability availability, ObjectMapper json, Messages messages, Clock clock,
              ToolRunRecorder recorder) {
        this.tools = tools;
        this.recorder = recorder;
        this.availability = availability;
        this.messages = messages;
        this.clock = clock;
        this.reader = json.reader().with(JsonNodeFeature.USE_BIG_DECIMAL_FOR_FLOATS)
                .without(JsonNodeFeature.STRIP_TRAILING_BIGDECIMAL_ZEROES);
    }

    /** The orders in {@code status}, newest first, whatever the hold reason or the day they were placed. */
    static String ordersIn(String status) {
        return """
                {"status":"%s","holdReason":"ANY","placedFrom":"","placedTo":"","limit":%d}""".formatted(status, ROWS);
    }

    /** The most recently changed purchase orders in {@code status}, from every supplier. */
    static String purchaseOrdersIn(String status) {
        return """
                {"status":"%s","poNumber":"","supplier":"","limit":%d}""".formatted(status, PURCHASE_ORDERS_READ);
    }

    /**
     * The card for {@code user}, read now: one section per thing their tools can look at, in a fixed order. Every read
     * is planned first and they run at once ({@link ParallelTools}), each as the user, through the tool's guard, as a
     * question's tools run (design §5.2); the sections are then built from the answers in that fixed order.
     */
    public Card of(CurrentUser user) {
        Instant generatedAt = clock.instant();
        Set<String> granted = Set.copyOf(availability.grantedTo(user));
        List<ToolCall> planned = new ArrayList<>();
        Reads lowStock = plan(granted, FIND_LOW_STOCK, planned, LOW_STOCK_ARGUMENTS);
        // One call answers both lists, each with its own total: the expired ones are the more urgent.
        Reads lots = plan(granted, FIND_EXPIRING_LOTS, planned, EXPIRING_LOTS_ARGUMENTS);
        Reads orders = plan(granted, FIND_ORDERS, planned, ordersIn("ON_HOLD"), ordersIn("PENDING_PAYMENT"));
        Reads purchaseOrders = plan(granted, FIND_PURCHASE_ORDERS, planned,
                SENT_STATUSES.stream().map(TodayCard::purchaseOrdersIn).toArray(String[]::new));
        Reads production = plan(granted, FIND_PRODUCTION_ORDERS, planned, OVERDUE_PRODUCTION_ARGUMENTS);
        List<ToolExecution> ran = new ArrayList<>();
        // Recorded whatever happens after the tools ran: the reads happened, so the trail has them.
        try {
            ran.addAll(tools.run(planned, ToolChannel.TODAY, finished -> { }));
            List<Section> sections = new ArrayList<>();
            if (lowStock != null) {
                sections.add(section(LOW_STOCK, lowStock.of(ran, 0), LOW_STOCK_ROWS, answer -> null));
            }
            if (lots != null) {
                sections.add(section(EXPIRED_LOTS, lots.of(ran, 0), EXPIRED_ROWS, answer -> nightlyCheck()));
                sections.add(section(EXPIRING_LOTS, lots.of(ran, 0), EXPIRING_ROWS, this::expiringNote));
            }
            if (orders != null) {
                sections.add(section(HELD_ORDERS, orders.of(ran, 0), HELD_ROWS, answer -> null));
                sections.add(section(AWAITING_PAYMENT, orders.of(ran, 1), AWAITING_PAYMENT_ROWS,
                        answer -> awaitingPayment()));
            }
            if (purchaseOrders != null) {
                sections.add(overduePurchaseOrders(purchaseOrders.all(ran)));
            }
            if (production != null) {
                sections.add(section(OVERDUE_PRODUCTION, production.of(ran, 0), OVERDUE_PRODUCTION_ROWS,
                        answer -> null));
            }
            log.info("Assistant today card for user {}: {}", user.userId(),
                    ran.isEmpty() ? "none of its tools is granted" : ran.stream()
                            .map(run -> run.call().name() + " " + run.outcome() + " " + run.durationMs() + " ms")
                            .collect(Collectors.joining(", ")));
            return new Card(generatedAt, List.copyOf(sections));
        } finally {
            recorder.record(ToolChannel.TODAY, user.userId(), ran);
        }
    }

    /**
     * Plans the calls of one tool, if the user is granted it, after those planned already; the id only pairs a call
     * with its result.
     *
     * @return where its answers will be, or null when the tool is not the user's: its sections are left out
     */
    private static Reads plan(Set<String> granted, String tool, List<ToolCall> planned, String... arguments) {
        if (!granted.contains(tool)) {
            return null;
        }
        Reads reads = new Reads(planned.size(), arguments.length);
        for (String argument : arguments) {
            planned.add(new ToolCall("today-" + (planned.size() + 1), tool, argument));
        }
        return reads;
    }

    /** The answers of one tool's planned calls: {@code count} executions from {@code first}, in planned order. */
    private record Reads(int first, int count) {

        ToolExecution of(List<ToolExecution> ran, int call) {
            return ran.get(first + call);
        }

        List<ToolExecution> all(List<ToolExecution> ran) {
            return ran.subList(first, first + count);
        }
    }

    private Section section(String key, ToolExecution run, Listing listing, Function<JsonNode, String> note) {
        if (run.outcome() != ToolOutcome.OK) {
            return unanswered(key, run);
        }
        String tool = run.call().name();
        try {
            JsonNode answer = read(run);
            return new Section(key, tool, ToolOutcome.OK, listing.table(key, tool, answer), note.apply(answer));
        } catch (UnreadableAnswer unreadable) {
            return unreadable(key, tool, unreadable);
        }
    }

    /**
     * The purchase orders a supplier is late with, the most days late first. {@code findPurchaseOrders} cannot filter
     * on lateness, so the card reads the orders sent to suppliers and keeps the rows the tool itself marks overdue: the
     * judgment (sent, not refused, still awaited, expected before today in Vietnam) stays the tool's. The tool lists
     * them earliest expected first, so a page that ends on a late order may be followed by more late ones: the table is
     * then truncated and the note says so. Either read failing fails the section, since half the list would read as
     * all of it.
     *
     * @param reads one answer per {@link #SENT_STATUSES}, in that order
     */
    private Section overduePurchaseOrders(List<ToolExecution> reads) {
        // By number: an order that moved from CONFIRMED to PARTIALLY_RECEIVED between the two reads counts once.
        Map<String, JsonNode> overdue = new LinkedHashMap<>();
        boolean readAll = true;
        for (ToolExecution run : reads) {
            if (run.outcome() != ToolOutcome.OK) {
                return unanswered(OVERDUE_PURCHASE_ORDERS, run);
            }
            try {
                JsonNode answer = read(run);
                JsonNode rows = Listing.rows(answer, "purchaseOrders");
                for (JsonNode order : rows) {
                    if (order.path("overdue").asBoolean(false)) {
                        overdue.putIfAbsent(order.path("poNumber").asText(), order);
                    }
                }
                // Earliest expected first: only a page that ends on a late order can be followed by more of them.
                boolean endsLate = !rows.isEmpty() && rows.get(rows.size() - 1).path("overdue").asBoolean(false);
                readAll &= !(answer.path("truncated").asBoolean(false) && endsLate);
            } catch (UnreadableAnswer unreadable) {
                return unreadable(OVERDUE_PURCHASE_ORDERS, FIND_PURCHASE_ORDERS, unreadable);
            }
        }
        List<JsonNode> late = overdue.values().stream().sorted(MOST_LATE_FIRST).toList();
        ResultTable.Table table = new ResultTable.Table(OVERDUE_PURCHASE_ORDERS, FIND_PURCHASE_ORDERS,
                OVERDUE_PURCHASE_ORDER_COLUMNS,
                late.stream().limit(ROWS).map(order -> cells(order, OVERDUE_PURCHASE_ORDER_COLUMNS)).toList(),
                late.size(), !readAll || late.size() > ROWS);
        return new Section(OVERDUE_PURCHASE_ORDERS, FIND_PURCHASE_ORDERS, ToolOutcome.OK, table,
                readAll ? null : purchaseOrdersLeftOut());
    }

    /**
     * A section whose tool did not answer: its outcome, no table and a note. INVALID or NOT_FOUND to the card's own
     * fixed arguments is a defect of the card, not something the user did, so it is logged; {@link AssistantTools}
     * has logged a failure already.
     */
    private Section unanswered(String key, ToolExecution run) {
        ToolOutcome outcome = run.outcome();
        if (outcome == ToolOutcome.INVALID || outcome == ToolOutcome.NOT_FOUND) {
            log.error("Assistant today card: {} answered {} to the arguments of section {}: {}", run.call().name(),
                    outcome, key, run.result());
        }
        return new Section(key, run.call().name(), outcome, null, outcome == ToolOutcome.DENIED ? denied() : failed());
    }

    private Section unreadable(String key, String tool, UnreadableAnswer unreadable) {
        log.error("Assistant today card: the answer of {} for section {} could not be read: {}", tool, key,
                unreadable.getMessage());
        return new Section(key, tool, ToolOutcome.FAILED, null, failed());
    }

    private JsonNode read(ToolExecution run) {
        try {
            return reader.readTree(run.result());
        } catch (JsonProcessingException malformed) {
            throw new UnreadableAnswer("it is not JSON");
        }
    }

    /** Every column of {@code row} in column order, null where the tool left the field out (absent nulls). */
    private static Map<String, Object> cells(JsonNode row, List<String> columns) {
        Map<String, Object> cells = new LinkedHashMap<>();
        for (String column : columns) {
            JsonNode value = row;
            for (String field : column.split("\\.")) {
                value = value.path(field);
            }
            cells.put(column, value.isMissingNode() ? NullNode.getInstance() : value);
        }
        return Collections.unmodifiableMap(cells);
    }

    private String denied() {
        return messages.get("assistant.today.denied",
                "Your permissions do not cover this part, so nothing of it is shown.");
    }

    private String failed() {
        return messages.get("assistant.today.failed",
                "This part could not be read; the error is logged. The other parts are not affected.");
    }

    /**
     * What "waiting for payment" leaves out, as findOrders' description tells the model: an accountant reading the
     * list as every order owing money would miss a held one and a packed one whose balance is due before delivery.
     */
    private String awaitingPayment() {
        return messages.get("assistant.today.awaitingPayment", "Orders waiting for their prepayment or deposit. One on "
                + "hold for payment is with the held orders; a packed order whose balance is due before delivery is "
                + "not listed.");
    }

    /**
     * The expiry quantities are the nightly sweep's count, not the stock now: the card says so as the tool's
     * description makes the model say it, or people plan a clearance for units picked that morning.
     */
    private String nightlyCheck() {
        return messages.get("assistant.today.nightlyCheck", "Quantities are as the last nightly expiry check counted "
                + "them: goods picked or received since then are not in them.");
    }

    /** The nightly-check note and, when the alerts follow fewer days ahead than the card asks for, that limit too. */
    private String expiringNote(JsonNode answer) {
        int window = answer.path("warningWindowDays").asInt(EXPIRY_DAYS);
        if (window >= EXPIRY_DAYS) {
            return nightlyCheck();
        }
        return nightlyCheck() + " " + messages.get("assistant.today.expiryWindow",
                "Expiry alerts follow only the lots within " + window + " days of their expiry date.", window);
    }

    private String purchaseOrdersLeftOut() {
        return messages.get("assistant.today.purchaseOrdersLeftOut", "Only the " + PURCHASE_ORDERS_READ + " purchase "
                + "orders expected earliest in each status sent to a supplier were checked, and the last of them is late "
                + "too: more may be.", PURCHASE_ORDERS_READ);
    }

    /**
     * @param generatedAt when the card was read
     * @param sections    one per thing the user's tools can look at, in the card's order
     */
    public record Card(Instant generatedAt, List<Section> sections) {

        public Card {
            sections = List.copyOf(sections);
        }
    }

    /**
     * @param key     what the section lists: {@code lowStock}, {@code expiredLots}, {@code expiringLots},
     *                {@code heldOrders}, {@code awaitingPayment}, {@code overduePurchaseOrders} or
     *                {@code overdueProduction}
     * @param tool    the tool that read it
     * @param outcome how the tool's run ended; only OK has a table
     * @param table   the rows, keyed by the section; each row has every column, a field the tool left out as a JSON
     *                null. Truncated when some matches are not listed, or for the late purchase orders may not have
     *                been read
     * @param note    what the reader needs to read the section right, in the user's language; null when nothing
     */
    public record Section(String key, String tool, ToolOutcome outcome, ResultTable.Table table, String note) {
    }

    /**
     * Where a section's rows are in its tool's answer, and which of their fields it shows.
     *
     * @param list      the answer's field holding the rows
     * @param total     the field counting every match
     * @param truncated the field saying some matches were left out
     * @param columns   field names of the tool's row, a nested one as {@code hold.reason}
     */
    private record Listing(String list, String total, String truncated, List<String> columns) {

        ResultTable.Table table(String key, String tool, JsonNode answer) {
            JsonNode count = answer.get(total);
            if (count == null || !count.isIntegralNumber()) {
                // findLowStock withholds its list, with no total, from a user limited to warehouses. The panel never
                // grants it to one; should it run for one anyway, a list without its count is not shown.
                throw new UnreadableAnswer("it has no " + total);
            }
            List<Map<String, Object>> rows = new ArrayList<>();
            for (JsonNode row : rows(answer, list)) {
                rows.add(cells(row, columns));
            }
            return new ResultTable.Table(key, tool, columns, rows, count.asLong(),
                    answer.path(truncated).asBoolean(false));
        }

        static JsonNode rows(JsonNode answer, String field) {
            JsonNode rows = answer.get(field);
            if (rows == null || !rows.isArray()) {
                throw new UnreadableAnswer("it has no list " + field);
            }
            return rows;
        }
    }

    /** An answer of a shape the card does not know; the section is FAILED and the reason logged. */
    private static final class UnreadableAnswer extends RuntimeException {

        UnreadableAnswer(String reason) {
            super(reason, null, false, false);
        }
    }
}
