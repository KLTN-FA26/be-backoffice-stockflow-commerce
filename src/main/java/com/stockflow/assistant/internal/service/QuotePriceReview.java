package com.stockflow.assistant.internal.service;

import com.stockflow.catalog.api.CatalogService;
import com.stockflow.catalog.api.PriceSchedule;
import com.stockflow.common.error.NotFoundException;
import com.stockflow.common.security.Action;
import com.stockflow.common.security.CurrentUser;
import com.stockflow.customer.api.CreditTerms;
import com.stockflow.customer.api.CustomerLabel;
import com.stockflow.customer.api.CustomerService;
import com.stockflow.order.api.CustomerSalesHistory;
import com.stockflow.order.api.QuoteCards;
import com.stockflow.payment.api.CreditPosition;
import com.stockflow.payment.api.PaymentService;
import com.stockflow.product.api.CustomerSkus;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * The panel beside the Sales Manager's quote price approval (contract §1.4, kltn-docs 14 BR-10): for each line, the
 * price quoted next to what this customer pays by its own price list, the standard price of the variant the SKU was
 * drawn on, and <b>what the customer was last actually sold the SKU at</b>; and, for a manager who may read it, the
 * customer's credit. Deterministic: no model.
 *
 * <p><b>What it adds to the screen.</b> The quote's own price check ({@code QuotePriceCheck}, shown on the quote) already
 * measures every price break against the customer's price, else the standard price, and is what decides whether the
 * quote needs approval. Two facts it does not give, and a manager otherwise looks up by hand, are the price this
 * customer agreed to last time ({@link CustomerSalesHistory}: a signed contract's order line) and whether the customer
 * is already late paying. The reference columns are repeated here at the line's proposed cartons so the panel reads on
 * its own; the approval rule stays the quote's, measured at each break's start, and this panel never overrides it.</p>
 *
 * <p><b>Reference</b>, per line: the customer's own price at the proposed cartons, else the standard price there (the
 * order of {@code QuotePriceCheck}), else the last price sold, else none. {@code deltaVsReference} is
 * {@code (quoted - reference) / reference x 100}, two decimals: negative is below the reference. A price schedule kept in
 * pieces ({@link PriceSchedule.Measure#UNIT}, from before 4B-2) is read at the line's pieces, not its cartons.</p>
 *
 * <p><b>Credit</b> is shown only to a caller holding {@code customer-credit-profiles:READ}, the permission of the
 * customer's credit position screen ({@code GET /api/v1/orders/credit-position/{customerId}}), and computed as that
 * screen does: the limit accounting approved, what the customer owes after delivery, what is overdue, and the limit less
 * what is owed. Anyone else gets null, never zeros.</p>
 *
 * <p><b>Bounded.</b> One read of the quote, one of the customer's label, one of the SKUs' variants, one of the last
 * sales; the customer's price and the standard price are read per SKU (the catalog's {@code api} answers one SKU at a
 * time), at most one call each per distinct SKU of the quote - as the quote's own check reads them.</p>
 */
@Component
public class QuotePriceReview {

    /** {@code CustomerResources.CREDIT_PROFILES}: the customer credit position screen's permission. */
    static final String CREDIT_PROFILES = "customer-credit-profiles";

    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    private final QuoteCards quotes;
    private final CustomerService customers;
    private final CatalogService catalog;
    private final CustomerSkus customerSkus;
    private final CustomerSalesHistory history;
    private final PaymentService payments;

    QuotePriceReview(QuoteCards quotes, CustomerService customers, CatalogService catalog, CustomerSkus customerSkus,
                     CustomerSalesHistory history, PaymentService payments) {
        this.quotes = quotes;
        this.customers = customers;
        this.catalog = catalog;
        this.customerSkus = customerSkus;
        this.history = history;
        this.payments = payments;
    }

    /** @throws NotFoundException for no such quote */
    public Review of(UUID quoteId, CurrentUser user) {
        QuoteCards.Card quote = quotes.quote(quoteId).orElseThrow(() -> new NotFoundException("Quote", quoteId));
        UUID customerId = quote.customerId();
        CustomerLabel label = customerId == null ? null : customers.labels(List.of(customerId)).get(customerId);
        Set<String> skus = new LinkedHashSet<>();
        quote.lines().stream().map(QuoteCards.Line::sku).filter(Objects::nonNull).forEach(skus::add);
        Map<String, Optional<PriceSchedule>> own = new HashMap<>();
        Map<String, Optional<PriceSchedule>> standard = new HashMap<>();
        Map<String, CustomerSalesHistory.LastSold> lastSold = Map.of();
        if (customerId != null && !skus.isEmpty()) {
            Map<String, String> variants = customerSkus.sourceSkusOf(skus);
            Map<String, Optional<PriceSchedule>> byVariant = new HashMap<>();
            for (String sku : skus) {
                own.put(sku, catalog.customerPrice(customerId, sku));
                String variant = variants.get(sku);
                standard.put(sku, variant == null ? Optional.empty()
                        : byVariant.computeIfAbsent(variant, catalog::standardPrice));
            }
            lastSold = history.lastSold(customerId, skus);
        }
        List<Line> lines = new ArrayList<>();
        for (QuoteCards.Line line : quote.lines()) {
            CustomerSalesHistory.LastSold last = line.sku() == null ? null : lastSold.get(line.sku());
            if (last != null && last.orderId() != null && last.orderId().equals(quote.convertedOrderId())) {
                // The quote's own order is not an earlier sale of it.
                last = null;
            }
            lines.add(line(line, own.getOrDefault(line.sku(), Optional.empty()),
                    standard.getOrDefault(line.sku(), Optional.empty()), last));
        }
        Credit credit = customerId != null && user.can(CREDIT_PROFILES, Action.READ) ? credit(customerId) : null;
        List<String> flags = new ArrayList<>();
        if (lines.stream().anyMatch(line -> line.flags().contains(BELOW_REFERENCE))) {
            flags.add("LINES_BELOW_REFERENCE");
        }
        if (lines.stream().anyMatch(line -> line.flags().contains(NO_REFERENCE))) {
            flags.add("LINES_WITHOUT_REFERENCE");
        }
        if (lines.stream().anyMatch(line -> line.flags().contains(BELOW_LAST_SOLD))) {
            flags.add("LINES_BELOW_LAST_SOLD");
        }
        if (credit != null && credit.overdue().signum() > 0) {
            flags.add("CREDIT_OVERDUE");
        }
        return new Review(quote.id(), quote.number(), quote.status(), customerId,
                label == null ? null : label.name(), label == null ? null : label.code(), lines, credit,
                List.copyOf(flags));
    }

    static final String BELOW_REFERENCE = "BELOW_REFERENCE";
    static final String NO_REFERENCE = "NO_REFERENCE";
    static final String BELOW_LAST_SOLD = "BELOW_LAST_SOLD";
    /** The line is not priced yet: nothing to measure. */
    static final String NOT_PRICED = "NOT_PRICED";
    /** Last sold in cartons of another count: a price per piece of another pack is not quite the same price. */
    static final String LAST_SOLD_OTHER_PACK = "LAST_SOLD_OTHER_PACK";

    static Line line(QuoteCards.Line line, Optional<PriceSchedule> own, Optional<PriceSchedule> standard,
                     CustomerSalesHistory.LastSold last) {
        BigDecimal quoted = line.unitPrice();
        BigDecimal customerPrice = own.flatMap(schedule -> priceAt(schedule, line)).orElse(null);
        BigDecimal standardPrice = standard.flatMap(schedule -> priceAt(schedule, line)).orElse(null);
        BigDecimal lastPrice = last == null ? null : last.unitPrice();
        String reference = customerPrice != null ? "CUSTOMER_PRICE" : standardPrice != null ? "STANDARD_PRICE"
                : lastPrice != null ? "LAST_SOLD" : null;
        BigDecimal referencePrice = customerPrice != null ? customerPrice : standardPrice != null ? standardPrice
                : lastPrice;
        BigDecimal delta = quoted == null || referencePrice == null || referencePrice.signum() == 0 ? null
                : quoted.subtract(referencePrice).multiply(HUNDRED).divide(referencePrice, 2, RoundingMode.HALF_UP);
        List<String> flags = new ArrayList<>();
        if (quoted == null) {
            flags.add(NOT_PRICED);
        }
        if (reference == null) {
            flags.add(NO_REFERENCE);
        } else if (delta != null && delta.signum() < 0) {
            flags.add(BELOW_REFERENCE);
        }
        if (quoted != null && lastPrice != null && quoted.compareTo(lastPrice) < 0) {
            flags.add(BELOW_LAST_SOLD);
        }
        if (last != null && last.unitsPerCarton() != null && line.unitsPerCarton() != null
                && !last.unitsPerCarton().equals(line.unitsPerCarton())) {
            flags.add(LAST_SOLD_OTHER_PACK);
        }
        return new Line(line.lineNo(), line.sku(), line.cartons(), line.unitsPerCarton(), quoted, customerPrice,
                standardPrice, lastPrice, last == null ? null : last.soldOn(),
                last == null ? null : last.orderNumber(), delta, reference, List.copyOf(flags));
    }

    /**
     * The schedule's price at the line's quantity: its break that has started by then, in cartons - or in pieces for a
     * schedule kept in pieces. Empty below its first break, and for a line with no quantity yet.
     */
    static Optional<BigDecimal> priceAt(PriceSchedule schedule, QuoteCards.Line line) {
        Integer cartons = line.cartons();
        if (cartons == null) {
            return Optional.empty();
        }
        int quantity = schedule.measure() == PriceSchedule.Measure.UNIT
                ? cartons * (line.unitsPerCarton() == null ? 1 : line.unitsPerCarton()) : cartons;
        BigDecimal price = null;
        int reached = Integer.MIN_VALUE;
        for (PriceSchedule.Tier tier : schedule.tiers()) {
            if (tier.minQuantity() <= quantity && tier.minQuantity() >= reached && tier.unitPrice() != null) {
                reached = tier.minQuantity();
                price = tier.unitPrice().amount();
            }
        }
        return Optional.ofNullable(price);
    }

    /** As {@code CreditHoldService.position}: the limit, what is owed after delivery, overdue, and what is left. */
    private Credit credit(UUID customerId) {
        CreditTerms terms = customers.creditTerms(customerId);
        CreditPosition owed = payments.creditPosition(customerId);
        BigDecimal outstanding = owed.outstandingReceivables() == null ? BigDecimal.ZERO
                : owed.outstandingReceivables();
        BigDecimal limit = terms.creditLimit();
        return new Credit(limit, outstanding, owed.overdueAmount() == null ? BigDecimal.ZERO : owed.overdueAmount(),
                limit == null ? null : limit.subtract(outstanding).max(BigDecimal.ZERO), owed.oldestOverdueDueDate(),
                terms.currency());
    }

    /**
     * @param customerName the customer as registered; the manager approving reads the customer's name on the quote
     * @param credit       null when the caller may not read the customer's credit
     */
    public record Review(UUID quoteId, String quoteNumber, String quoteStatus, UUID customerId, String customerName,
                  String customerCode, List<Line> lines, Credit credit, List<String> flags) {
    }

    /**
     * @param quotedUnitPrice  the price of one piece at the proposed cartons; null while the line is not priced
     * @param customerPrice    the customer's own price at the proposed cartons; null without one there
     * @param standardPrice    the standard price of the SKU's variant at the proposed cartons; null without one there
     * @param lastSoldUnitPrice the price of one piece on the customer's latest signed order line of the SKU
     * @param deltaVsReference percent, negative when below the reference
     */
    public record Line(int lineNo, String sku, Integer cartons, Integer unitsPerCarton, BigDecimal quotedUnitPrice,
                BigDecimal customerPrice, BigDecimal standardPrice, BigDecimal lastSoldUnitPrice,
                java.time.LocalDate lastSoldOn, String lastSoldOrderNumber, BigDecimal deltaVsReference,
                String reference, List<String> flags) {
    }

    /**
     * @param limit     the credit limit accounting approved; null for a customer without credit
     * @param available the limit less what is owed, never below zero; null without a limit
     */
    public record Credit(BigDecimal limit, BigDecimal outstanding, BigDecimal overdue, BigDecimal available,
                  java.time.LocalDate oldestOverdueDueDate, String currency) {
    }
}
