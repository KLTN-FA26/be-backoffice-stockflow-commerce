package com.stockflow.order.internal.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stockflow.common.api.PageResponse;
import com.stockflow.common.audit.AuditAction;
import com.stockflow.common.audit.Auditable;
import com.stockflow.common.domain.Sku;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.error.NotFoundException;
import com.stockflow.common.id.Identifiers;
import com.stockflow.common.persistence.Pages;
import com.stockflow.common.persistence.SortWhitelist;
import com.stockflow.common.security.CurrentUser;
import com.stockflow.common.security.Role;
import com.stockflow.common.security.ScopeGuards;
import com.stockflow.customer.api.CommercialTerm;
import com.stockflow.customer.api.CustomerService;
import com.stockflow.design.api.DesignService;
import com.stockflow.order.api.OrderService;
import com.stockflow.order.api.OrderSummary;
import com.stockflow.order.api.PlaceOrderCommand;
import com.stockflow.order.internal.domain.PriceTier;
import com.stockflow.order.internal.domain.ProductionRecords;
import com.stockflow.order.internal.domain.Quote;
import com.stockflow.order.internal.domain.QuoteLine;
import com.stockflow.order.internal.domain.QuoteRepository;
import com.stockflow.order.internal.domain.QuoteStatus;
import com.stockflow.order.internal.repository.QuoteSearch;
import com.stockflow.catalog.api.CatalogService;
import com.stockflow.catalog.api.PriceSchedule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Quotes on one transaction per use case (SCRUM-448).
 *
 * <p><b>Accept</b> checks, under the quote's row lock: the version the customer saw, the validity
 * (BR-ORD-008), an ACTIVE customer still allowed the quote's payment term (BR-ORD-007), and for every
 * line a sample this customer approved for that design on that blank — or a reorder of what was already
 * printed for them (plan D4). The sample facts come from {@code ordering.approved_sample} and
 * {@code order_line_production}, which the order module keeps from production's events; the order module
 * never reads production, which depends on it (no cycle).</p>
 *
 * <p><b>Convert</b> places the order through {@link QuotedOrders} in the same transaction, at the
 * quantities and prices fixed at acceptance, with a request id derived from the quote: a second call — or
 * a concurrent one waiting on the row lock — finds the quote CONVERTED and returns its order, and even a
 * call that slipped past the lock would hit the order's request-id idempotency. If placing fails (stock,
 * credit terms, addresses) the whole conversion rolls back and the quote stays ACCEPTED.</p>
 */
@Service
@Transactional
class QuoteService implements Quotes {

    private static final Logger log = LoggerFactory.getLogger(QuoteService.class);
    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Ho_Chi_Minh");
    private static final SortWhitelist SORT = SortWhitelist
            .of("createdAt", "validUntil", "quoteNumber", "status")
            .withDefault("createdAt", Sort.Direction.DESC);

    private final CatalogService catalog;
    private final QuoteRepository quotes;
    private final QuoteSearch search;
    private final ProductionRecords production;
    private final CustomerService customers;
    private final DesignService designs;
    private final QuotedOrders quotedOrders;
    private final OrderService orders;
    private final ObjectMapper json;
    private final Clock clock;

    QuoteService(QuoteRepository quotes, QuoteSearch search, ProductionRecords production, CustomerService customers,
                 DesignService designs, QuotedOrders quotedOrders, OrderService orders, ObjectMapper json, Clock clock,
                 CatalogService catalog) {
        this.catalog = catalog;
        this.quotes = quotes;
        this.search = search;
        this.production = production;
        this.customers = customers;
        this.designs = designs;
        this.quotedOrders = quotedOrders;
        this.orders = orders;
        this.json = json;
        this.clock = clock;
    }

    @Override
    @Auditable(action = AuditAction.CREATE, resourceType = "quote", resourceId = "#result?.id()")
    public QuoteView create(CurrentUser user, Create command) {
        ScopeGuards.requireBackOffice(user);
        Objects.requireNonNull(command.requestId(), "requestId");
        UUID customerId = Objects.requireNonNull(command.customerId(), "customerId");
        quotes.lockRequest(command.requestId());
        Optional<Quote> earlier = quotes.findByRequestId(command.requestId());
        if (earlier.isPresent()) {
            if (!earlier.get().customerId().equals(customerId)) {
                throw new BusinessException(ErrorCode.IDEMPOTENCY_KEY_REUSED);
            }
            return view(earlier.get());
        }
        requireNewWorkFor(customerId);
        Quote.Offer offer = offer(customerId, command.draft());
        requireTermAllowed(customerId, offer);
        Quote quote = Quote.draft(Identifiers.newId(), quotes.nextNumber(today()), command.requestId(), customerId, offer);
        quotes.save(quote);
        quotes.recordTransition(quote.id(), 1, null, QuoteStatus.DRAFT, null, null, clock.instant(), user.userId());
        log.info("Quote {} drafted for customer {} ({} line(s))", quote.quoteNumber(), customerId, offer.lines().size());
        return view(quote);
    }

    @Override
    @Transactional(readOnly = true)
    public PageResponse<Row> list(CurrentUser user, Criteria criteria) {
        Optional<UUID> own = portalCustomer(user);
        var page = search.search(new QuoteSearch.Criteria(own.orElse(criteria.customerId()), criteria.statuses(),
                criteria.search(), own.isPresent()), Pages.of(criteria.page(), criteria.size(),
                SORT.parse(criteria.sort())));
        Instant now = clock.instant();
        return Pages.toResponse(page.map(r -> new Row(r.id(), r.quoteNumber(), r.customerId(),
                r.status() == QuoteStatus.SENT && !now.isBefore(r.validUntil()) ? QuoteStatus.EXPIRED : r.status(),
                r.revision(), r.paymentTerm(), r.validUntil(), r.sentAt(), r.convertedOrderId(), r.createdAt(),
                r.version())));
    }

    @Override
    @Transactional(readOnly = true)
    public QuoteView get(CurrentUser user, UUID quoteId) {
        Quote quote = quotes.findById(quoteId).orElseThrow(() -> new NotFoundException("quote", quoteId));
        requireVisible(user, quote);
        return view(quote);
    }

    @Override
    @Auditable(action = AuditAction.UPDATE, resourceType = "quote", resourceId = "#quoteId")
    public QuoteView edit(CurrentUser user, UUID quoteId, long version, Draft draft) {
        return backOffice(user, quoteId, version, quote -> {
            Quote.Offer offer = offer(quote.customerId(), draft);
            requireTermAllowed(quote.customerId(), offer);
            quote.revise(offer);
        }, null);
    }

    @Override
    @Auditable(action = AuditAction.TRANSITION, resourceType = "quote", resourceId = "#quoteId")
    public QuoteView send(CurrentUser user, UUID quoteId, long version) {
        ScopeGuards.requireBackOffice(user);
        Quote quote = lock(user, quoteId, version);
        requireNewWorkFor(quote.customerId());
        requireTermAllowed(quote.customerId(), quote.offer());
        QuoteStatus from = quote.status();
        quote.send(user.userId(), clock.instant());
        quotes.save(quote);
        quotes.recordTransition(quote.id(), quote.revision(), from, QuoteStatus.SENT, null, offerJson(quote),
                quote.sentAt(), user.userId());
        log.info("Quote {} revision {} sent", quote.quoteNumber(), quote.revision());
        return view(quote);
    }

    @Override
    @Auditable(action = AuditAction.APPROVE, resourceType = "quote", resourceId = "#quoteId")
    public QuoteView accept(CurrentUser user, UUID quoteId, Acceptance acceptance) {
        requireCustomerAnswer(user);
        Quote quote = lock(user, quoteId, acceptance.version());
        Instant now = clock.instant();
        requireNewWorkFor(quote.customerId());
        requireTermAllowed(quote.customerId(), quote.offer());
        // Validity before the sample rule: an expired quote is answered "expired", whatever its samples.
        if (quote.statusAt(now) == QuoteStatus.EXPIRED) {
            throw new BusinessException(ErrorCode.QUOTE_EXPIRED, "Quote " + quote.quoteNumber() + " has expired");
        }
        List<String> missing = new ArrayList<>();
        for (QuoteLine line : quote.offer().lines()) {
            if (!gate(quote.customerId(), line).satisfied()) {
                missing.add("line %d (%s)".formatted(line.lineNo(), line.blankSku().code()));
            }
        }
        if (!missing.isEmpty()) {
            throw new BusinessException(ErrorCode.DESIGN_SAMPLE_NOT_APPROVED,
                    "Quote %s has new designs without a sample this customer approved: %s (BR-ORD-008)"
                            .formatted(quote.quoteNumber(), String.join(", ", missing)));
        }
        QuoteStatus from = quote.status();
        quote.accept(acceptance.quantities(), user.userId(), now);
        quotes.save(quote);
        quotes.recordTransition(quote.id(), quote.revision(), from, QuoteStatus.ACCEPTED, null, null, now,
                user.userId());
        log.info("Quote {} accepted", quote.quoteNumber());
        return view(quote);
    }

    @Override
    @Auditable(action = AuditAction.REJECT, resourceType = "quote", resourceId = "#quoteId")
    public QuoteView requestChanges(CurrentUser user, UUID quoteId, long version, String feedback) {
        requireCustomerAnswer(user);
        Quote quote = lock(user, quoteId, version);
        QuoteStatus from = quote.status();
        quote.requestChanges(feedback, user.userId(), clock.instant());
        quotes.save(quote);
        quotes.recordTransition(quote.id(), quote.revision(), from, QuoteStatus.CHANGES_REQUESTED,
                quote.customerFeedback(), null, quote.respondedAt(), user.userId());
        return view(quote);
    }

    @Override
    @Auditable(action = AuditAction.TRANSITION, resourceType = "quote", resourceId = "#quoteId")
    public QuoteView reopen(CurrentUser user, UUID quoteId, long version) {
        return backOffice(user, quoteId, version, Quote::reopen, null);
    }

    @Override
    @Auditable(action = AuditAction.TRANSITION, resourceType = "quote", resourceId = "#quoteId")
    public QuoteView cancel(CurrentUser user, UUID quoteId, long version, String reason) {
        return backOffice(user, quoteId, version, quote -> quote.cancel(reason), reason);
    }

    @Override
    @Auditable(action = AuditAction.CREATE, resourceType = "quote", resourceId = "#quoteId")
    public QuoteView convert(CurrentUser user, UUID quoteId, Conversion conversion) {
        ScopeGuards.requireBackOffice(user);
        Quote quote = quotes.findByIdForUpdate(Objects.requireNonNull(quoteId, "quoteId"))
                .orElseThrow(() -> new NotFoundException("quote", quoteId));
        if (quote.status() == QuoteStatus.CONVERTED) {
            return view(quote);   // the one order already exists: a retry is answered, not repeated
        }
        requireVersion(quote, conversion.version());
        if (quote.status() != QuoteStatus.ACCEPTED) {
            throw new BusinessException(ErrorCode.INVALID_QUOTE_TRANSITION,
                    "Quote %s is %s; only an accepted quote becomes an order".formatted(quote.quoteNumber(),
                            quote.status()));
        }
        List<PlaceOrderCommand.Line> lines = quote.offer().lines().stream()
                .map(line -> new PlaceOrderCommand.Line(line.blankSku(), line.acceptedQuantity(),
                        line.acceptedUnitPrice(), line.designSnapshotId()))
                .toList();
        OrderSummary order = quotedOrders.placeQuotedOrder(new PlaceOrderCommand(conversionRequestId(quote.id()),
                quote.customerId(), conversion.shippingAddressId(), conversion.billingAddressId(), true, lines,
                quote.offer().paymentTerm(), quote.offer().depositPercent()));
        Instant now = clock.instant();
        quote.convert(order.orderId(), now);
        quotes.save(quote);
        quotes.recordTransition(quote.id(), quote.revision(), QuoteStatus.ACCEPTED, QuoteStatus.CONVERTED,
                order.orderNumber(), null, now, user.userId());
        log.info("Quote {} converted into order {} ({})", quote.quoteNumber(), order.orderNumber(), order.status());
        return view(quote);
    }

    @Override
    @Auditable(action = AuditAction.CREATE, resourceType = "quote", resourceId = "#result?.id()")
    public QuoteView reorder(CurrentUser user, Reorder command) {
        UUID requestId = Objects.requireNonNull(command.requestId(), "requestId");
        UUID orderId = Objects.requireNonNull(command.orderId(), "orderId");
        Optional<UUID> own = portalCustomer(user);
        OrderSummary order = orders.findById(orderId)
                .filter(o -> own.isEmpty() || own.get().equals(o.customerId()))
                .orElseThrow(() -> new NotFoundException("order", orderId));
        quotes.lockRequest(requestId);
        Optional<Quote> earlier = quotes.findByRequestId(requestId);
        if (earlier.isPresent()) {
            if (!earlier.get().customerId().equals(order.customerId())) {
                throw new BusinessException(ErrorCode.IDEMPOTENCY_KEY_REUSED);
            }
            return view(earlier.get());
        }
        Quote source = quotes.findByConvertedOrderId(orderId).orElseThrow(() -> new BusinessException(
                ErrorCode.ORDER_NOT_QUOTED, "Order %s was bought at the price list".formatted(order.orderNumber())));
        UUID customerId = source.customerId();
        requireNewWorkFor(customerId);
        Quote.Offer old = source.offer();
        Instant now = clock.instant();
        // The deal stays with its own quote and order: every line starts again from today's price list.
        List<Line> lines = old.lines().stream()
                .map(line -> new Line(line.blankSku().code(), line.designSnapshotId(), line.printSpec(),
                        line.acceptedQuantity() == null ? line.quantity() : line.acceptedQuantity(),
                        catalog.priceSchedule(customerId, line.blankSku().code()).tiers().stream()
                                .map(t -> new Tier(t.minQuantity(), t.unitPrice().amount())).toList()))
                .toList();
        // The source quote's validity window, counted from now; Sales sets the real one before sending.
        Instant validUntil = now.plus(java.time.Duration.between(source.sentAt(), old.validUntil()));
        Quote.Offer offer = offer(customerId, new Draft(old.paymentTerm(), old.depositPercent(), validUntil,
                old.terms(), lines));
        requireTermAllowed(customerId, offer);
        Quote quote = Quote.draft(Identifiers.newId(), quotes.nextNumber(today()), requestId, customerId, offer);
        quotes.save(quote);
        quotes.recordTransition(quote.id(), 1, null, QuoteStatus.DRAFT, "Reorder of order " + order.orderNumber(),
                null, now, user.userId());
        log.info("Quote {} drafted to reorder order {} (from quote {})", quote.quoteNumber(), order.orderNumber(),
                source.quoteNumber());
        return view(quote);
    }

    @Override
    public int expireDue(int limit) {
        Instant now = clock.instant();
        int expired = 0;
        for (UUID id : quotes.sentAndExpiredBy(now, limit)) {
            Quote quote = quotes.findByIdForUpdate(id).orElse(null);
            if (quote != null && quote.expire(now)) {
                quotes.save(quote);
                quotes.recordTransition(quote.id(), quote.revision(), QuoteStatus.SENT, QuoteStatus.EXPIRED, null,
                        null, now, null);
                expired++;
            }
        }
        return expired;
    }

    private QuoteView backOffice(CurrentUser user, UUID quoteId, long version, Consumer<Quote> change, String reason) {
        ScopeGuards.requireBackOffice(user);
        Quote quote = lock(user, quoteId, version);
        QuoteStatus from = quote.status();
        int revision = quote.revision();
        change.accept(quote);
        quotes.save(quote);
        if (quote.status() != from) {
            quotes.recordTransition(quote.id(), quote.revision(), from, quote.status(), reason, null, clock.instant(),
                    user.userId());
        }
        log.debug("Quote {} {} -> {} (revision {} -> {})", quote.quoteNumber(), from, quote.status(), revision,
                quote.revision());
        return view(quote);
    }

    private Quote lock(CurrentUser user, UUID quoteId, long version) {
        Quote quote = quotes.findByIdForUpdate(Objects.requireNonNull(quoteId, "quoteId"))
                .orElseThrow(() -> new NotFoundException("quote", quoteId));
        requireVisible(user, quote);
        requireVersion(quote, version);
        return quote;
    }

    private void requireVersion(Quote quote, long expected) {
        long current = search.findRow(quote.id()).orElseThrow().version();
        if (current != expected) {
            throw new BusinessException(ErrorCode.OPTIMISTIC_LOCK,
                    "Quote %s changed since version %d (now %d)".formatted(quote.quoteNumber(), expected, current));
        }
    }

    /**
     * A portal user sees only their own customer's quotes, and only once sent: another customer's quote and
     * Sales' working drafts answer 404, so neither is revealed.
     */
    private void requireVisible(CurrentUser user, Quote quote) {
        Optional<UUID> own = portalCustomer(user);
        if (own.isPresent() && (!own.get().equals(quote.customerId()) || !quote.status().seenByCustomer())) {
            throw new NotFoundException("quote", quote.id());
        }
    }

    /** The answer is the customer's own (03 §18 "Customer (portal)"); staff do not answer for them. */
    private void requireCustomerAnswer(CurrentUser user) {
        if (portalCustomer(user).isEmpty()) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "Only the customer answers their quote");
        }
    }

    /** The customer a portal user acts for; empty for anyone holding a back-office role. */
    private Optional<UUID> portalCustomer(CurrentUser user) {
        Objects.requireNonNull(user, "user");
        boolean portalOnly = !user.roles().isEmpty() && user.roles().stream().allMatch(role -> role == Role.CUSTOMER);
        if (!portalOnly) {
            return Optional.empty();
        }
        return Optional.of(customers.findByUserId(user.userId())
                .orElseThrow(() -> new BusinessException(ErrorCode.CUSTOMER_NOT_FOUND)).customerId());
    }

    /** BR-ORD-007: quotes go to ACTIVE B2B customers only. */
    private void requireNewWorkFor(UUID customerId) {
        String status = customers.findById(customerId)
                .orElseThrow(() -> new BusinessException(ErrorCode.CUSTOMER_NOT_FOUND)).status();
        if (!"ACTIVE".equals(status)) {
            throw new BusinessException(ErrorCode.CUSTOMER_NOT_ACTIVE,
                    "Customer " + customerId + " is " + status + "; quotes are for ACTIVE customers (BR-ORD-007)");
        }
    }

    /** kltn-docs 15 BR-01: a quote offers only a payment term the customer's commercial terms allow. */
    private void requireTermAllowed(UUID customerId, Quote.Offer offer) {
        if (!customers.creditTerms(customerId).allows(CommercialTerm.valueOf(offer.paymentTerm().name()))) {
            throw new BusinessException(ErrorCode.PAYMENT_TERM_NOT_ALLOWED,
                    "Customer %s may not be offered %s terms".formatted(customerId, offer.paymentTerm()));
        }
    }

    /**
     * Builds and verifies the offer: every design must be a snapshot this customer confirmed, for the
     * product the blank belongs to. An edit replaces the lines (new ids); nothing was accepted on a draft.
     */
    private Quote.Offer offer(UUID customerId, Draft draft) {
        Objects.requireNonNull(draft, "draft");
        if (draft.lines() == null || draft.lines().isEmpty()) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "A quote needs at least one line");
        }
        if (draft.validUntil() == null || !draft.validUntil().isAfter(clock.instant())) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "A quote is valid until a moment still ahead");
        }
        List<QuoteLine> lines = new ArrayList<>();
        Map<String, Integer> blankUnits = new java.util.HashMap<>();
        draft.lines().forEach(line -> blankUnits.merge(new Sku(line.blankSku()).code(), line.quantity(), Integer::sum));
        int lineNo = 1;
        for (Line line : draft.lines()) {
            Sku blank = new Sku(line.blankSku());
            designs.verifySnapshotForSku(line.designSnapshotId(), customerId, blank.code());
            List<PriceTier> tiers = line.tiers() == null || line.tiers().isEmpty()
                    ? defaultTiers(customerId, blank, blankUnits.get(blank.code()))
                    : line.tiers().stream().map(t -> PriceTier.of(t.minQuantity(), t.unitPrice())).toList();
            lines.add(new QuoteLine(Identifiers.newId(), lineNo++, blank, line.designSnapshotId(), line.printSpec(),
                    line.quantity(), tiers, null, null));
        }
        return new Quote.Offer(draft.paymentTerm(), draft.depositPercent(),
                draft.validUntil().truncatedTo(java.time.temporal.ChronoUnit.MICROS), draft.terms(), lines);
    }

    /**
     * kltn-docs 14 step 2: a quote's price starts from the customer's price list - its own tiers for the
     * blank, else its group's or the base price (SCRUM-428). Copied into the quote, so a later price-list
     * change does not move a quote while it is valid (14 BR-03). Sales may enter any valid tiers instead: no
     * discount limit and no internal approval (user decision 2026-10-12, overriding 14 BR-10); the customer
     * accepts.
     *
     * <p>{@code quantity} is the draft's total of the blank, every design together (P5). Below the list's first
     * tier the list has no price for the quantity (user decision P4, 2026-10-12):
     * Sales negotiates and enters the line's tiers - the first tier's price is never applied to a smaller
     * quantity, and the refusal says so instead of reading as a quote MOQ the customer never saw.</p>
     */
    private List<PriceTier> defaultTiers(UUID customerId, Sku blank, int quantity) {
        PriceSchedule schedule;
        try {
            schedule = catalog.priceSchedule(customerId, blank.code());
        } catch (BusinessException noPrice) {
            if (noPrice.errorCode() != ErrorCode.PRICE_NOT_AVAILABLE) throw noPrice;
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "No price list price for " + blank.code() + "; enter the line's price tiers");
        }
        if (quantity < schedule.moq()) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "%d units of %s is below the customer's price list minimum of %d; enter the negotiated price tiers"
                            .formatted(quantity, blank.code(), schedule.moq()));
        }
        return schedule.tiers().stream()
                .map(t -> PriceTier.of(t.minQuantity(), t.unitPrice().amount())).toList();
    }

    private Quotes.SampleGate gate(UUID customerId, QuoteLine line) {
        Optional<UUID> sample = production.approvedSampleOf(customerId, line.designSnapshotId(), line.blankSku().code());
        if (sample.isPresent()) {
            return new Quotes.SampleGate(true, sample.get(), false);
        }
        boolean reorder = production.producedBeforeFor(customerId, line.designSnapshotId(), line.blankSku().code());
        return new Quotes.SampleGate(reorder, null, reorder);
    }

    /** Derived from the quote, so every conversion attempt of one quote names the same order request. */
    static UUID conversionRequestId(UUID quoteId) {
        return UUID.nameUUIDFromBytes(("quote-conversion:" + quoteId).getBytes(StandardCharsets.UTF_8));
    }

    /** The offer as sent, kept on the SENT history row: what the customer saw at that revision. */
    private String offerJson(Quote quote) {
        Quote.Offer offer = quote.offer();
        Map<String, Object> document = new LinkedHashMap<>();
        document.put("revision", quote.revision());
        document.put("paymentTerm", offer.paymentTerm().name());
        document.put("depositPercent", offer.depositPercent());
        document.put("validUntil", offer.validUntil().toString());
        document.put("terms", offer.terms());
        document.put("lines", offer.lines().stream().map(line -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("lineNo", line.lineNo());
            row.put("blankSku", line.blankSku().code());
            row.put("designSnapshotId", line.designSnapshotId().toString());
            row.put("printSpec", line.printSpec());
            row.put("quantity", line.quantity());
            row.put("tiers", line.tiers().stream().map(t -> Map.of("minQuantity", t.minQuantity(),
                    "unitPrice", t.unitPrice().amount())).toList());
            return row;
        }).toList());
        try {
            return json.writeValueAsString(document);
        } catch (JsonProcessingException impossible) {
            throw new IllegalStateException("Could not record the offer of quote " + quote.quoteNumber(), impossible);
        }
    }

    private QuoteView view(Quote quote) {
        Instant now = clock.instant();
        QuoteSearch.Row row = search.findRow(quote.id()).orElseThrow();
        BigDecimal acceptedTotal = null;
        BigDecimal proposedTotal = BigDecimal.ZERO;
        List<LineView> lines = new ArrayList<>();
        for (QuoteLine line : quote.offer().lines()) {
            BigDecimal lineTotal = line.acceptedQuantity() == null ? null
                    : line.acceptedUnitPrice().amount().multiply(BigDecimal.valueOf(line.acceptedQuantity()));
            if (lineTotal != null) {
                acceptedTotal = acceptedTotal == null ? lineTotal : acceptedTotal.add(lineTotal);
            }
            BigDecimal proposedPrice = line.priceFor(quote.offer().proposedUnits(line.blankSku().code())).amount();
            BigDecimal proposedLineTotal = proposedPrice.multiply(BigDecimal.valueOf(line.quantity()));
            proposedTotal = proposedTotal.add(proposedLineTotal);
            lines.add(new LineView(line.id(), line.lineNo(), line.blankSku().code(), line.designSnapshotId(),
                    line.printSpec(), line.quantity(), line.moq(),
                    line.tiers().stream().map(t -> new Tier(t.minQuantity(), t.unitPrice().amount())).toList(),
                    proposedPrice, proposedLineTotal,
                    line.acceptedQuantity(), line.acceptedUnitPrice() == null ? null : line.acceptedUnitPrice().amount(),
                    lineTotal, gate(quote.customerId(), line)));
        }
        ConvertedOrder converted = quote.convertedOrderId() == null ? null : orders.findById(quote.convertedOrderId())
                .map(o -> new ConvertedOrder(o.orderId(), o.orderNumber(), o.status(), o.total().amount()))
                .orElse(null);
        List<Transition> history = search.history(quote.id()).stream()
                .map(t -> new Transition(t.revision(), t.fromStatus(), t.toStatus(), t.reason(), t.changedAt(),
                        t.changedBy()))
                .toList();
        Quote.Offer offer = quote.offer();
        return new QuoteView(quote.id(), quote.quoteNumber(), quote.customerId(), quote.statusAt(now), quote.revision(),
                offer.paymentTerm(), offer.depositPercent(), offer.validUntil(), offer.terms(), lines, proposedTotal,
                acceptedTotal,
                quote.sentAt(), quote.sentBy(), quote.respondedAt(), quote.respondedBy(), quote.customerFeedback(),
                quote.cancellationReason(), converted, quote.convertedAt(), history, row.createdAt(), row.createdBy(),
                row.version());
    }

    private LocalDate today() {
        return LocalDate.ofInstant(clock.instant(), BUSINESS_ZONE);
    }
}
