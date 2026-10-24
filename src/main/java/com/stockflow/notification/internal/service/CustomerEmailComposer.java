package com.stockflow.notification.internal.service;

import com.stockflow.contracts.OrderCancelled;
import com.stockflow.contracts.OrderDelivered;
import com.stockflow.contracts.OrderPlaced;
import com.stockflow.contracts.OrderRemainderClosed;
import com.stockflow.contracts.ShipmentStatusChanged;
import com.stockflow.notification.internal.service.EmailContent.Fact;
import com.stockflow.notification.internal.service.EmailContent.Table;

import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.ResourceBundle;
import java.util.Set;

/**
 * Decides which customer e-mail an order event calls for, and words it (SCRUM-352).
 *
 * <p>Every word comes from {@code i18n/messages_<locale>.properties} under {@code email.*}; the customers are
 * Vietnamese and {@code stockflow.notification.email.locale} is {@code vi}. Amounts read the Vietnamese way
 * ({@code 1.250.000 ₫}), times in the business's zone ({@code Asia/Ho_Chi_Minh}), whatever the server's own.</p>
 *
 * <h2>What a customer is told, and what not</h2>
 * <ul>
 *   <li>Facts the event carries - numbers, codes, amounts, the carrier's name - never what someone typed: an order
 *       cancellation's {@code note} is Sales' or the customer's free text and stays in the back office.</li>
 *   <li>A reason as a sentence from the bundle, except {@link #UNDISCLOSED_REASONS}: telling a suspected fraudster why
 *       the order stopped helps no one but them; the message then only asks the customer to contact Sales.</li>
 *   <li>No tracking number: no carrier is called, so none exists (kltn-docs 09 §1).</li>
 * </ul>
 */
@Component
class CustomerEmailComposer {

    static final String ORDER_PLACED = "order.placed";
    static final String HANDED_OVER = "shipment.handed-over";
    static final String SHIPMENT_DELIVERED = "shipment.delivered";
    static final String DELIVERY_FAILED = "shipment.delivery-failed";
    static final String ORDER_DELIVERED = "order.delivered";
    static final String ORDER_CANCELLED = "order.cancelled";
    static final String REMAINDER_CLOSED = "order.remainder-closed";

    static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Ho_Chi_Minh");

    /** Lines listed in one message; an order of hundreds lists these and says how many more. */
    static final int MAX_LINES = 50;

    /** A greeting longer than this is cut: a company name is not a paragraph, whatever was typed into it. */
    static final int MAX_NAME_LENGTH = 120;

    private static final Set<String> UNDISCLOSED_REASONS = Set.of("SUSPECTED_FRAUD");

    private static final DateTimeFormatter WHEN = DateTimeFormatter.ofPattern("HH:mm dd/MM/yyyy").withZone(BUSINESS_ZONE);

    private final EmailProperties properties;

    CustomerEmailComposer(EmailProperties properties) {
        this.properties = properties;
    }

    /** Order placed: what was ordered and its total. A guest order (no account) goes to the address it carried. */
    Optional<CustomerMessage> orderPlaced(OrderPlaced event) {
        return Optional.of(new CustomerMessage(ORDER_PLACED, event.orderId().toString(), event.customerId(),
                event.customerId() == null ? event.contactEmail() : null,
                addressee -> {
                    ResourceBundle m = bundle();
                    List<Fact> facts = List.of(
                            new Fact(m.getString("email.customer.fact.order"), event.orderNumber()),
                            new Fact(m.getString("email.customer.fact.total"),
                                    money(event.totalAmount(), event.currency())));
                    return content(m, ORDER_PLACED, addressee, event.orderNumber(), facts,
                            orderLines(m, event.lines(), event.currency()));
                }));
    }

    /**
     * kltn-docs 09 §4.1 step 3 and §4.3: the customer hears when the goods are handed to the carrier (which carrier, no
     * tracking number), when they are delivered, and when a delivery fails. Packing, a return to the warehouse and a
     * cancelled shipment are the shop's own steps; an order cancellation has its own message.
     */
    Optional<CustomerMessage> shipmentChanged(ShipmentStatusChanged event) {
        String code = switch (event.status()) {
            case "HANDED_OVER" -> HANDED_OVER;
            case "DELIVERED" -> SHIPMENT_DELIVERED;
            case "DELIVERY_FAILED" -> DELIVERY_FAILED;
            default -> null;
        };
        if (code == null) {
            return Optional.empty();
        }
        // One message per change: a shipment handed over again after a failed delivery is news again. The event's own
        // id names the change; without one (it is unique per change, but nothing stops a publisher leaving it out) the
        // shipment and the step's time do - the step is in the template code. Kept under the log's 100 characters.
        String source = event.eventId() != null ? event.eventId().toString()
                : event.shipmentId() + "@" + (event.occurredAt() == null ? "" : event.occurredAt().toEpochMilli());
        return Optional.of(new CustomerMessage(code, source, event.customerId(), null,
                addressee -> {
                    ResourceBundle m = bundle();
                    List<Fact> facts = new ArrayList<>();
                    facts.add(new Fact(m.getString("email.customer.fact.order"), event.orderNumber()));
                    facts.add(new Fact(m.getString("email.customer.fact.shipment"), event.shipmentCode()));
                    if (HANDED_OVER.equals(code)) {
                        if (filled(event.carrierName())) {
                            facts.add(new Fact(m.getString("email.customer.fact.carrier"), event.carrierName()));
                        }
                        if (event.packageCount() > 0) {
                            facts.add(new Fact(m.getString("email.customer.fact.packages"),
                                    Integer.toString(event.packageCount())));
                        }
                    }
                    if (DELIVERY_FAILED.equals(code) && event.failureReasonCode() != null) {
                        reason(m, "email.shipment.failure-reason.", event.failureReasonCode())
                                .ifPresent(reason -> facts.add(new Fact(m.getString("email.customer.fact.reason"),
                                        reason)));
                    }
                    if (event.occurredAt() != null) {
                        facts.add(new Fact(m.getString("email." + code + ".at"), WHEN.format(event.occurredAt())));
                    }
                    return content(m, code, addressee, event.orderNumber(), facts, null);
                }));
    }

    /**
     * An order delivered whole, without shipments (the order-level handover of kltn-docs 09). An order delivered
     * through shipments was already told per shipment, so that event - {@code shipmentId} set - calls for nothing.
     */
    Optional<CustomerMessage> orderDelivered(OrderDelivered event) {
        if (event.shipmentId() != null) {
            return Optional.empty();
        }
        return Optional.of(new CustomerMessage(ORDER_DELIVERED, event.orderId().toString(), event.customerId(), null,
                addressee -> {
                    ResourceBundle m = bundle();
                    List<Fact> facts = new ArrayList<>();
                    facts.add(new Fact(m.getString("email.customer.fact.order"), event.orderNumber()));
                    if (event.deliveredAt() != null) {
                        facts.add(new Fact(m.getString("email.order.delivered.at"), WHEN.format(event.deliveredAt())));
                    }
                    return content(m, ORDER_DELIVERED, addressee, event.orderNumber(), facts, null);
                }));
    }

    /** kltn-docs 17 §4.5: the order is cancelled; what is refunded and what fee is still owed (Q6a), if any. */
    Optional<CustomerMessage> orderCancelled(OrderCancelled event) {
        return Optional.of(new CustomerMessage(ORDER_CANCELLED, event.orderId().toString(), event.customerId(), null,
                addressee -> {
                    ResourceBundle m = bundle();
                    List<Fact> facts = new ArrayList<>();
                    facts.add(new Fact(m.getString("email.customer.fact.order"), event.orderNumber()));
                    reason(m, "email.order.cancellation-reason.", event.reasonCode())
                            .ifPresent(reason -> facts.add(new Fact(m.getString("email.customer.fact.reason"),
                                    reason)));
                    if (event.cancelledAt() != null) {
                        facts.add(new Fact(m.getString("email.order.cancelled.at"), WHEN.format(event.cancelledAt())));
                    }
                    List<String> outro = money(m, facts, event.refundableAmount(), event.owedAmount(),
                            event.currency());
                    return content(m, ORDER_CANCELLED, addressee, event.orderNumber(), facts, null, outro);
                }));
    }

    /**
     * Part of a partly fulfilled order closed (decision G4-Q3): which lines will not come, what is refunded and what
     * fee is owed. One message per closure, so a second closure of the same order is told too.
     */
    Optional<CustomerMessage> remainderClosed(OrderRemainderClosed event) {
        String source = event.closureId() != null ? event.closureId().toString()
                : event.orderId() + "@" + (event.closedAt() == null ? "" : event.closedAt().toEpochMilli());
        return Optional.of(new CustomerMessage(REMAINDER_CLOSED, source, event.customerId(), null,
                addressee -> {
                    ResourceBundle m = bundle();
                    List<Fact> facts = new ArrayList<>();
                    facts.add(new Fact(m.getString("email.customer.fact.order"), event.orderNumber()));
                    reason(m, "email.order.cancellation-reason.", event.reasonCode())
                            .ifPresent(reason -> facts.add(new Fact(m.getString("email.customer.fact.reason"),
                                    reason)));
                    if (event.closedAt() != null) {
                        facts.add(new Fact(m.getString("email.order.remainder-closed.at"),
                                WHEN.format(event.closedAt())));
                    }
                    List<String> outro = money(m, facts, event.refundableAmount(), event.owedAmount(),
                            event.currency());
                    return content(m, REMAINDER_CLOSED, addressee, event.orderNumber(), facts,
                            closedLines(m, event.lines()), outro);
                }));
    }

    // ------------------------------------------------------------------------------------------------ wording

    private EmailContent content(ResourceBundle m, String code, String addressee, String orderNumber,
                                 List<Fact> facts, Table table) {
        return content(m, code, addressee, orderNumber, facts, table, List.of());
    }

    private EmailContent content(ResourceBundle m, String code, String addressee, String orderNumber,
                                 List<Fact> facts, Table table, List<String> extraOutro) {
        String number = orderNumber == null ? "" : orderNumber;
        String intro = m.getString("email." + code + ".intro").formatted(number);
        List<String> outro = new ArrayList<>(extraOutro);
        outro.add(m.getString("email." + code + ".outro"));
        String name = displayName(addressee);
        return new EmailContent(
                m.getString("email." + code + ".subject").formatted(number),
                intro,
                properties.senderName(),
                m.getString("email." + code + ".heading"),
                name == null ? m.getString("email.customer.greeting.anonymous")
                        : m.getString("email.customer.greeting").formatted(name),
                List.of(intro),
                facts,
                table,
                outro,
                m.getString("email.customer.closing"),
                properties.senderName(),
                m.getString(properties.replyTo() == null ? "email.customer.footer.no-reply"
                        : "email.customer.footer.reply"));
    }

    /** The refund and the fee as facts, and a sentence for each that is there. */
    private static List<String> money(ResourceBundle m, List<Fact> facts, BigDecimal refundable, BigDecimal owed,
                                      String currency) {
        List<String> outro = new ArrayList<>();
        if (positive(refundable)) {
            facts.add(new Fact(m.getString("email.customer.fact.refund"), money(refundable, currency)));
            outro.add(m.getString("email.customer.refund"));
        }
        if (positive(owed)) {
            facts.add(new Fact(m.getString("email.customer.fact.owed"), money(owed, currency)));
            outro.add(m.getString("email.customer.owed"));
        }
        return outro;
    }

    private static Table orderLines(ResourceBundle m, List<OrderPlaced.OrderLine> lines, String currency) {
        if (lines == null || lines.isEmpty()) {
            return null;
        }
        List<List<String>> rows = lines.stream().limit(MAX_LINES)
                .map(line -> List.of(text(line.sku()), Integer.toString(line.quantity()),
                        money(line.unitPrice(), currency),
                        line.unitPrice() == null ? ""
                                : money(line.unitPrice().multiply(BigDecimal.valueOf(line.quantity())), currency)))
                .toList();
        return new Table(List.of(m.getString("email.customer.table.sku"), m.getString("email.customer.table.quantity"),
                m.getString("email.customer.table.unit-price"), m.getString("email.customer.table.line-total")),
                rows, more(m, lines.size()));
    }

    private static Table closedLines(ResourceBundle m, List<OrderRemainderClosed.Line> lines) {
        if (lines == null || lines.isEmpty()) {
            return null;
        }
        List<List<String>> rows = lines.stream().limit(MAX_LINES)
                .map(line -> List.of(text(line.sku()), Integer.toString(line.quantity())))
                .toList();
        return new Table(List.of(m.getString("email.customer.table.sku"),
                m.getString("email.customer.table.closed-quantity")), rows, more(m, lines.size()));
    }

    private static String more(ResourceBundle m, int lines) {
        return lines > MAX_LINES ? m.getString("email.customer.table.more").formatted(lines - MAX_LINES) : null;
    }

    /** A reason's sentence, when the customer may be told it and the bundle has one. */
    private static Optional<String> reason(ResourceBundle m, String prefix, String code) {
        if (code == null || UNDISCLOSED_REASONS.contains(code) || !m.containsKey(prefix + code)) {
            return Optional.empty();
        }
        return Optional.of(m.getString(prefix + code));
    }

    private ResourceBundle bundle() {
        return ResourceBundle.getBundle("i18n.messages", properties.locale());
    }

    // ------------------------------------------------------------------------------------------------ formats

    /**
     * An amount as a Vietnamese customer reads it: {@code 1.250.000 ₫} - dots between thousands, no decimals for the
     * dong. Another currency keeps two decimals and its code. Written out rather than taken from {@code NumberFormat}
     * for the locale, whose output (the space before the symbol, the symbol itself) differs between JDK versions.
     */
    static String money(BigDecimal amount, String currency) {
        if (amount == null) {
            return "";
        }
        var symbols = new DecimalFormatSymbols(Locale.ROOT);
        symbols.setGroupingSeparator('.');
        symbols.setDecimalSeparator(',');
        boolean dong = currency == null || "VND".equalsIgnoreCase(currency);
        var format = new DecimalFormat(dong ? "#,##0" : "#,##0.00", symbols);
        format.setRoundingMode(RoundingMode.HALF_UP);
        return format.format(amount) + (dong ? " ₫" : " " + currency.toUpperCase(Locale.ROOT));
    }

    /**
     * The name to greet: one line, at most {@value #MAX_NAME_LENGTH} characters; null when there is nothing to greet
     * by. Control characters become spaces - the HTML is escaped anyway, but a line break would still break the
     * text part's layout.
     */
    static String displayName(String name) {
        if (name == null) {
            return null;
        }
        StringBuilder line = new StringBuilder(name.length());
        name.codePoints().forEach(c -> line.appendCodePoint(Character.isISOControl(c) ? ' ' : c));
        String single = line.toString().replaceAll("\\s+", " ").strip();
        if (single.isEmpty()) {
            return null;
        }
        if (single.codePointCount(0, single.length()) <= MAX_NAME_LENGTH) {
            return single;
        }
        return single.substring(0, single.offsetByCodePoints(0, MAX_NAME_LENGTH - 1)).strip() + "…";
    }

    private static String text(String value) {
        return value == null ? "" : value;
    }

    private static boolean filled(String value) {
        return value != null && !value.isBlank();
    }

    private static boolean positive(BigDecimal amount) {
        return amount != null && amount.signum() > 0;
    }
}
