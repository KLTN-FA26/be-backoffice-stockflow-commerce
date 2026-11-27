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
 * Under the seeded grants that keeps held orders from anyone who may not read orders, and receipts from anyone who may
 * not read receipts, as their screens do (§13 AQ-15).</p>
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
 * (the status of the held orders) is left out.</p>
 *
 * <p><b>The queues of the business as it is now</b> (ADR-0010): orders held, orders waiting for an instalment due on
 * signing, orders waiting for stock a producer is printing (AWAITING_STOCK), and receipts whose cartons wait to be put away.
 * Low stock, expiring lots, late purchase orders and the in-house production queue went with the rescope.</p>
 */
@Service
public class TodayCard {

    private static final Logger log = LoggerFactory.getLogger(TodayCard.class);

    /** Rows a section lists; its total counts every match. A card to glance at, not a report. */
    static final int ROWS = 10;

    static final String HELD_ORDERS = "heldOrders";
    static final String AWAITING_PAYMENT = "awaitingPayment";
    static final String AWAITING_STOCK = "awaitingStock";
    static final String RECEIPTS_TO_PUT_AWAY = "receiptsToPutAway";

    private static final String FIND_ORDERS = "findOrders";
    private static final String FIND_GOODS_RECEIPTS = "findGoodsReceipts";

    static final String RECEIPTS_TO_PUT_AWAY_ARGUMENTS = """
            {"status":"IN_PUTAWAY","receiptNumber":"","limit":%d}""".formatted(ROWS);

    private static final Listing HELD_ROWS = new Listing("orders", "total", "truncated", List.of("orderNumber",
            "placedAt", "total", "currency", "hold.reason", "hold.note", "hold.statusBeforeHold"));
    private static final Listing ORDER_ROWS = new Listing("orders", "total", "truncated",
            List.of("orderNumber", "placedAt", "total", "currency"));
    private static final Listing RECEIPT_ROWS = new Listing("receipts", "total", "truncated",
            List.of("receiptNumber", "productionOrderCode", "producerName", "warehouseCode", "receivedAt",
                    "confirmedAt"));

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
        return ("{\"status\":\"%s\",\"holdReason\":\"ANY\",\"placedFrom\":\"\",\"placedTo\":\"\",\"customer\":\"\","
                + "\"limit\":%d}").formatted(status, ROWS);
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
        Reads orders = plan(granted, FIND_ORDERS, planned, ordersIn("ON_HOLD"), ordersIn("PENDING_PAYMENT"),
                ordersIn("AWAITING_STOCK"));
        Reads receipts = plan(granted, FIND_GOODS_RECEIPTS, planned, RECEIPTS_TO_PUT_AWAY_ARGUMENTS);
        List<ToolExecution> ran = new ArrayList<>();
        // Recorded whatever happens after the tools ran: the reads happened, so the trail has them.
        try {
            ran.addAll(tools.run(planned, ToolChannel.TODAY, finished -> { }));
            List<Section> sections = new ArrayList<>();
            if (orders != null) {
                sections.add(section(HELD_ORDERS, orders.of(ran, 0), HELD_ROWS, answer -> null));
                sections.add(section(AWAITING_PAYMENT, orders.of(ran, 1), ORDER_ROWS, answer -> awaitingPayment()));
                sections.add(section(AWAITING_STOCK, orders.of(ran, 2), ORDER_ROWS, answer -> null));
            }
            if (receipts != null) {
                sections.add(section(RECEIPTS_TO_PUT_AWAY, receipts.of(ran, 0), RECEIPT_ROWS, answer -> null));
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
            // A note marked as instruction-like is marked for a model; the card is read by a person, no model.
            cells.put(column, value.isMissingNode() ? NullNode.getInstance()
                    : value.isTextual() && value.textValue().startsWith(TypedText.MARKER)
                    ? com.fasterxml.jackson.databind.node.TextNode.valueOf(TypedText.unmarked(value.textValue()))
                    : value);
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
     * list as every order owing money would miss a held one and a packed one whose instalment due before delivery is
     * unpaid.
     */
    private String awaitingPayment() {
        return messages.get("assistant.today.awaitingPayment", "Orders waiting for an instalment due on signing. One "
                + "on hold for payment or credit is with the held orders; an order whose instalment due before delivery "
                + "is unpaid is not listed.");
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
     * @param key     what the section lists: {@code heldOrders}, {@code awaitingPayment}, {@code awaitingStock} or
     *                {@code receiptsToPutAway}
     * @param tool    the tool that read it
     * @param outcome how the tool's run ended; only OK has a table
     * @param table   the rows, keyed by the section; each row has every column, a field the tool left out as a JSON
     *                null. Truncated when some matches are not listed
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
                // A list without its count is not shown: it would read as all of it.
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
