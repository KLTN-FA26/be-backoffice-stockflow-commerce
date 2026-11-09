package com.stockflow.order.internal.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stockflow.common.api.FieldError;
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
import com.stockflow.common.security.ScopeGuards;
import com.stockflow.customer.api.CommercialTerm;
import com.stockflow.customer.api.CustomerService;
import com.stockflow.order.api.OrderService;
import com.stockflow.order.api.OrderSummary;
import com.stockflow.order.api.PlaceOrderCommand;
import com.stockflow.order.internal.domain.PriceTier;
import com.stockflow.order.internal.domain.Quote;
import com.stockflow.order.internal.domain.QuoteLine;
import com.stockflow.order.internal.domain.QuoteRepository;
import com.stockflow.order.internal.domain.QuoteStatus;
import com.stockflow.order.internal.repository.QuoteSearch;
import com.stockflow.product.api.ProductService;
import com.stockflow.product.api.Purchasability;
import com.stockflow.catalog.api.CatalogService;
import com.stockflow.contracts.CustomerActedOnQuote;
import org.springframework.context.ApplicationEventPublisher;
import com.stockflow.catalog.api.PriceSchedule;
import com.stockflow.inventory.api.UomConversion;
import com.stockflow.inventory.api.UomConversionService;
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
 * (BR-ORD-008) and an ACTIVE customer still allowed the quote's payment term (BR-ORD-007). Print samples are
 * approved on the Production Order, outside the quote (kltn-docs 17), so acceptance does not wait for one.</p>
 *
 * <p><b>Convert</b> places the order through {@link QuotedOrders} in the same transaction, at the
 * quantities and prices fixed at acceptance, with a request id derived from the quote: a second call — or
 * a concurrent one waiting on the row lock — finds the quote CONVERTED and returns its order, and even a
 * call that slipped past the lock would hit the order's request-id idempotency. If placing fails (stock,
 * credit terms, addresses) the whole conversion rolls back and the quote stays ACCEPTED.</p>
 *
 * <p><b>Active SKUs only</b> (review of #109, item 6; BR-PRD-005): drafting, revising, sending, accepting and
 * converting each ask again whether every SKU on the quote is still ACTIVE, the answer held until the step
 * commits. An old quote is no exception. A line at fault is named; Sales puts an active SKU on it and sends
 * the quote again - nothing is substituted. A reorder draft is the one step that does not ask: it is a request
 * for Sales to look at, and its sending asks. Orders already placed carry on whatever happens to the product.</p>
 *
 * <p><b>The customer's own SKUs, by the carton</b> (4B-2; kltn-docs 14 BR-04, BR-07, BR-08, 13 BR-02): drafting,
 * revising, sending, accepting and converting each ask again that every SKU on the quote is one of this customer's own
 * - another customer's answers as if it did not exist, a stock variant or blank is refused - and a line is written in
 * the SKU's one carton unit, its pieces per carton fixed on the line. A line starts from the customer's own price for
 * the SKU, read at the quote's cartons of it; Sales enters the tiers when there is none. A quote drafted before on
 * blanks and designs is kept to read (QD-16): it is not sent, accepted or converted, and an accepted one may be
 * cancelled for that alone.</p>
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
    private final CustomerService customers;
    private final UomConversionService units;
    private final QuotedOrders quotedOrders;
    private final OrderService orders;
    private final ObjectMapper json;
    private final Clock clock;
    private final ApplicationEventPublisher events;
    private final ProductService products;

    private final com.stockflow.product.api.CustomerSkus customerSkus;

    QuoteService(QuoteRepository quotes, QuoteSearch search, CustomerService customers,
                 UomConversionService units, QuotedOrders quotedOrders, OrderService orders, ObjectMapper json,
                 Clock clock, CatalogService catalog, ApplicationEventPublisher events, ProductService products,
                 com.stockflow.product.api.CustomerSkus customerSkus) {
        this.customerSkus = customerSkus;
        this.events = events;
        this.products = products;
        this.catalog = catalog;
        this.quotes = quotes;
        this.search = search;
        this.customers = customers;
        this.units = units;
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
        requireActiveSkus(null, offer);
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
        return view(quote, portalCustomer(user).isPresent());
    }

    @Override
    @Auditable(action = AuditAction.UPDATE, resourceType = "quote", resourceId = "#quoteId")
    public QuoteView edit(CurrentUser user, UUID quoteId, long version, Draft draft) {
        return backOffice(user, quoteId, version, quote -> {
            Quote.Offer offer = offer(quote.customerId(), draft);
            requireTermAllowed(quote.customerId(), offer);
            requireActiveSkus(quote.quoteNumber(), offer);
            quote.revise(offer);
        }, null);
    }

    @Override
    @Auditable(action = AuditAction.TRANSITION, resourceType = "quote", resourceId = "#quoteId")
    public QuoteView send(CurrentUser user, UUID quoteId, long version) {
        ScopeGuards.requireBackOffice(user);
        Quote quote = lock(user, quoteId, version);
        requireCustomerSkus(quote, "sent");
        requireNewWorkFor(quote.customerId());
        requireTermAllowed(quote.customerId(), quote.offer());
        requireActiveSkus(quote.quoteNumber(), quote.offer());
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
        Quote quote = lockToAnswer(user, quoteId, acceptance.version());
        Instant now = clock.instant();
        requireNewWorkFor(quote.customerId());
        requireTermAllowed(quote.customerId(), quote.offer());
        if (quote.statusAt(now) == QuoteStatus.EXPIRED) {
            throw new BusinessException(ErrorCode.QUOTE_EXPIRED, "Quote " + quote.quoteNumber() + " has expired");
        }
        requireCustomerSkus(quote, "accepted");
        requireActiveSkus(quote.quoteNumber(), quote.offer());
        QuoteStatus from = quote.status();
        quote.accept(acceptance.cartons(), user.userId(), now);
        quotes.save(quote);
        quotes.recordTransition(quote.id(), quote.revision(), from, QuoteStatus.ACCEPTED, null, null, now,
                user.userId());
        notifySales(quote, CustomerActedOnQuote.Action.ACCEPTED, null, quote.sentBy(), now);
        log.info("Quote {} accepted", quote.quoteNumber());
        return view(quote);
    }

    @Override
    @Auditable(action = AuditAction.REJECT, resourceType = "quote", resourceId = "#quoteId")
    public QuoteView requestChanges(CurrentUser user, UUID quoteId, long version, String feedback) {
        requireCustomerAnswer(user);
        Quote quote = lockToAnswer(user, quoteId, version);
        QuoteStatus from = quote.status();
        quote.requestChanges(feedback, user.userId(), clock.instant());
        quotes.save(quote);
        quotes.recordTransition(quote.id(), quote.revision(), from, QuoteStatus.CHANGES_REQUESTED,
                quote.customerFeedback(), null, quote.respondedAt(), user.userId());
        // The feedback stays on the quote: the event carries facts, not what the customer typed (B-4).
        notifySales(quote, CustomerActedOnQuote.Action.CHANGES_REQUESTED, null, quote.sentBy(),
                quote.respondedAt());
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
        return backOffice(user, quoteId, version, quote -> {
            // A legacy quote is never converted (QD-16): accepted, it is cancelled for that alone.
            if (quote.status() == QuoteStatus.ACCEPTED && !quote.offer().legacy()) {
                requireAStoppedSku(quote);
            }
            quote.cancel(reason);
        }, reason);
    }

    /**
     * Decision 2026-10-17: an accepted quote becomes its order; Sales cancels it - with its reason, kept in the
     * history - only when a SKU on it is no longer active, so it can never be converted, and sends a new quote with an
     * active SKU if the customer still wants the goods. Held under the product lock until the cancel commits, like
     * every SKU check of a quote, so a SKU resumed meanwhile is converted instead.
     */
    private void requireAStoppedSku(Quote quote) {
        Map<String, Purchasability> verdicts = products.lockedPurchasability(
                quote.offer().lines().stream().map(line -> line.sku().code()).toList());
        boolean stopped = quote.offer().lines().stream().map(line -> verdicts.get(
                line.sku().code().toUpperCase(java.util.Locale.ROOT))).anyMatch(v -> v != null && !v.isPurchasable());
        if (!stopped) {
            throw new BusinessException(ErrorCode.INVALID_QUOTE_TRANSITION,
                    "Quote %s is accepted and every SKU on it is active: convert it into its order".formatted(
                            quote.quoteNumber()));
        }
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
        requireCustomerSkus(quote, "converted");
        requireActiveSkus(quote.quoteNumber(), quote.offer(), "cancel the quote with its reason and send a new one with "
                + "an active SKU");
        // The order lines are what was accepted: the pieces, the price of one, and the cartons they were ordered in
        // (14 §4.2 step 7, BR-08). The SKU is the design (12 §4.5), so no design snapshot travels with the line.
        List<PlaceOrderCommand.Line> lines = quote.offer().lines().stream()
                .map(line -> new PlaceOrderCommand.Line(line.sku(), line.acceptedUnits(), line.acceptedUnitPrice(),
                        null, quote.offer().requestedDeliveryDate(), new PlaceOrderCommand.Carton(
                                line.uomConversionId(), line.acceptedQuantity(), line.unitsPerCarton())))
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
        // The deal stays with its own quote and order: every line starts again from the customer's own prices today,
        // and a line they cannot price is left unpriced for Sales (R1) - never the old price, never a zero.
        Map<String, Integer> cartons = reorderedCartons(customerId, old);
        List<Line> lines = cartons.entrySet().stream()
                .map(entry -> new Line(entry.getKey(), entry.getValue(),
                        listTiers(customerId, entry.getKey(), entry.getValue())))
                .toList();
        // The source quote's validity window, counted from now; Sales sets the real one before sending.
        Instant validUntil = now.plus(java.time.Duration.between(source.sentAt(), old.validUntil()));
        // R1b: a term the customer may use today. The old one is kept only while still allowed - never restored
        // (a lost credit stays lost); otherwise the draft starts from the customer's default term, with no deposit
        // share carried over. Send, acceptance and conversion check the term again.
        com.stockflow.customer.api.CreditTerms current = customers.creditTerms(customerId);
        boolean keepTerm = current.allows(CommercialTerm.valueOf(old.paymentTerm().name()));
        com.stockflow.order.api.PaymentTerm term = keepTerm ? old.paymentTerm()
                : com.stockflow.order.api.PaymentTerm.valueOf(current.defaultTerm().name());
        Quote.Offer offer = offer(customerId, new Draft(term, keepTerm ? old.depositPercent() : null, validUntil,
                old.terms(), lines), true);
        requireTermAllowed(customerId, offer);
        Quote quote = Quote.draft(Identifiers.newId(), quotes.nextNumber(today()), requestId, customerId, offer);
        quotes.save(quote);
        String reason = keepTerm ? "Reorder of order " + order.orderNumber()
                : "Reorder of order %s; its payment term %s is no longer allowed, drafted as %s"
                        .formatted(order.orderNumber(), old.paymentTerm(), term);
        quotes.recordTransition(quote.id(), 1, null, QuoteStatus.DRAFT, reason, null, now, user.userId());
        if (own.isPresent()) {
            // The customer asked: the draft waits for the Sales user who negotiated the order's quote.
            notifySales(quote, CustomerActedOnQuote.Action.REORDER_REQUESTED, order.orderNumber(), source.sentBy(), now);
        }
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
        Quote quote = lockVisible(user, quoteId);
        requireVersion(quote, version);
        return quote;
    }

    /**
     * The customer's answer (accept, ask for changes) to a quote Sales cancelled meanwhile - a revision reopened and
     * dropped: told so, with Sales' reason, before the version - which the cancellation itself moved - so they read why
     * and not "changed since".
     */
    private Quote lockToAnswer(CurrentUser user, UUID quoteId, long version) {
        Quote quote = lockVisible(user, quoteId);
        if (quote.status() == QuoteStatus.CANCELLED) {
            throw new BusinessException(ErrorCode.INVALID_QUOTE_TRANSITION, "Quote %s was withdrawn: %s".formatted(
                    quote.quoteNumber(), quote.cancellationReason()));
        }
        requireVersion(quote, version);
        return quote;
    }

    private Quote lockVisible(CurrentUser user, UUID quoteId) {
        Quote quote = quotes.findByIdForUpdate(Objects.requireNonNull(quoteId, "quoteId"))
                .orElseThrow(() -> new NotFoundException("quote", quoteId));
        requireVisible(user, quote);
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
        if (own.isPresent() && (!own.get().equals(quote.customerId()) || !quote.seenByCustomer())) {
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
        if (!user.isPortalCustomer()) {
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

    /**
     * Every SKU on the offer is still ACTIVE - product APPROVED or PUBLISHED, variant ACTIVE - and stays so
     * until this step commits ({@link ProductService#lockedPurchasability}). One refusal names every line at
     * fault with why, on {@code lines[i].sku}, so the form can put it next to the SKU.
     */
    private void requireActiveSkus(String quoteNumber, Quote.Offer offer) {
        requireActiveSkus(quoteNumber, offer, "change it to an active SKU and send the quote again");
    }

    /** {@code next}: what Sales does about it - revise and send again, or, for an accepted quote, cancel it. */
    private void requireActiveSkus(String quoteNumber, Quote.Offer offer, String next) {
        List<QuoteLine> lines = offer.lines();
        Map<String, Purchasability> verdicts = products.lockedPurchasability(
                lines.stream().map(line -> line.sku().code()).toList());
        List<FieldError> errors = new ArrayList<>();
        List<String> named = new ArrayList<>();
        for (int i = 0; i < lines.size(); i++) {
            QuoteLine line = lines.get(i);
            String sku = line.sku().code();
            Purchasability verdict = verdicts.get(sku.toUpperCase(java.util.Locale.ROOT));
            if (verdict != null && !verdict.isPurchasable()) {
                errors.add(new FieldError("lines[" + i + "].sku",
                        "SKU %s on line %d is no longer active".formatted(sku, line.lineNo()), verdict.name()));
                named.add("line %d (%s, %s)".formatted(line.lineNo(), sku, verdict));
            }
        }
        if (!errors.isEmpty()) {
            throw new BusinessException(ErrorCode.SKU_NOT_SELLABLE, "%s holds a SKU that is no longer active: %s; "
                    .formatted(quoteNumber == null ? "The quote" : "Quote " + quoteNumber, String.join(", ", named))
                    + next, errors);
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
     * The cartons a reorder asks of each SKU: what the source quote accepted (or proposed), a legacy quote's designs as
     * the SKUs issued for them, its units rounded up to whole cartons. In the source's order, one line per SKU.
     */
    private Map<String, Integer> reorderedCartons(UUID customerId, Quote.Offer old) {
        Map<String, Integer> cartons = new LinkedHashMap<>();
        if (!old.legacy()) {
            old.lines().forEach(line -> cartons.merge(line.sku().code(), line.acceptedQuantity() == null
                    ? line.quantity() : line.acceptedQuantity(), Math::addExact));
            return cartons;
        }
        Map<UUID, com.stockflow.product.api.CustomerSku> issued = customerSkus.ofSnapshots(
                old.lines().stream().map(QuoteLine::designSnapshotId).toList());
        List<String> unissued = old.lines().stream().filter(line -> !issued.containsKey(line.designSnapshotId()))
                .map(line -> "line " + line.lineNo()).toList();
        if (!unissued.isEmpty()) {
            throw new BusinessException(ErrorCode.CUSTOMER_SKU_REQUIRED, ("The design on %s of the order has no SKU of "
                    + "the customer yet: quote it once its design is approved").formatted(String.join(", ", unissued)));
        }
        Map<String, UomConversion> cartonUnits = cartonUnits(issued.values().stream()
                .map(com.stockflow.product.api.CustomerSku::sku).distinct().toList());
        for (QuoteLine line : old.lines()) {
            String sku = issued.get(line.designSnapshotId()).sku();
            int units = line.acceptedQuantity() == null ? line.quantity() : line.acceptedQuantity();
            int factor = cartonUnits.get(sku).factor();
            cartons.merge(sku, Math.ceilDiv(units, factor), Math::addExact);
        }
        return cartons;
    }

    /**
     * The customer's own tiers for a SKU on a reorder of {@code cartons}, or none when the customer has no price of its
     * own for it or its first tier starts above (R1): Sales prices that line.
     */
    private List<Tier> listTiers(UUID customerId, String sku, int cartons) {
        return ownPrice(customerId, sku).filter(schedule -> cartons >= schedule.moq())
                .map(schedule -> schedule.tiers().stream()
                        .map(t -> new Tier(t.minQuantity(), t.unitPrice().amount())).toList())
                .orElse(List.of());
    }

    /**
     * The customer's own price for one of its SKUs (13 BR-05: tiers by cartons, a price per piece), if it has one. A
     * group or base price is a stock variant's price by the unit (13 §3) and is not offered for a SKU of the customer.
     */
    private Optional<PriceSchedule> ownPrice(UUID customerId, String sku) {
        return catalog.customerPrice(customerId, sku);
    }

    private Quote.Offer offer(UUID customerId, Draft draft) {
        return offer(customerId, draft, false);
    }

    /**
     * Builds and verifies the offer: every line is cartons of one of this customer's own SKUs, written in the SKU's
     * carton unit at its factor now (14 BR-08). An edit replaces the lines (new ids); nothing was accepted on a draft.
     *
     * @param asGiven take each line's tiers as they are, none meaning not priced yet (a reorder, R1), instead
     *                of filling omitted tiers from the customer's own price
     */
    private Quote.Offer offer(UUID customerId, Draft draft, boolean asGiven) {
        Objects.requireNonNull(draft, "draft");
        if (draft.lines() == null || draft.lines().isEmpty()) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "A quote needs at least one line");
        }
        if (draft.validUntil() == null || !draft.validUntil().isAfter(clock.instant())) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "A quote is valid until a moment still ahead");
        }
        List<String> skus = draft.lines().stream().map(line -> new Sku(line.sku()).code()).toList();
        requireOwnSkus(customerId, skus);
        Map<String, UomConversion> cartonUnits = cartonUnits(skus);
        Map<String, Integer> cartonsBySku = new java.util.HashMap<>();
        draft.lines().forEach(line -> cartonsBySku.merge(new Sku(line.sku()).code(), line.cartons(), Integer::sum));
        List<QuoteLine> lines = new ArrayList<>();
        int lineNo = 1;
        for (Line line : draft.lines()) {
            Sku sku = new Sku(line.sku());
            UomConversion carton = cartonUnits.get(sku.code());
            List<PriceTier> tiers = !asGiven && (line.tiers() == null || line.tiers().isEmpty())
                    ? defaultTiers(customerId, sku, cartonsBySku.get(sku.code()))
                    : line.tiers().stream().map(t -> PriceTier.of(t.minQuantity(), t.unitPrice())).toList();
            lines.add(QuoteLine.carton(Identifiers.newId(), lineNo++, sku, carton.id(), carton.factor(), line.cartons(),
                    tiers, null, null));
        }
        BigDecimal deposit = draft.depositPercent();
        if (draft.paymentTerm() == com.stockflow.order.api.PaymentTerm.DEPOSIT && deposit == null) {
            // Audit of #109: the share the customer is shown and accepts is the share its order asks for. The customer's
            // default is copied in now, not read again at conversion, when it may have changed since.
            com.stockflow.customer.api.CreditTerms terms = customers.creditTerms(customerId);
            deposit = terms == null ? null : terms.depositPercent();
        }
        if (draft.requestedDeliveryDate() != null && draft.requestedDeliveryDate().isBefore(today())) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "The requested delivery date cannot be in the past");
        }
        return new Quote.Offer(draft.paymentTerm(), deposit,
                draft.validUntil().truncatedTo(java.time.temporal.ChronoUnit.MICROS), draft.terms(),
                draft.requestedDeliveryDate(), lines);
    }

    /**
     * kltn-docs 14 BR-04, 13 BR-02, at every step: each SKU is one of this customer's own. Another customer's answers
     * {@code VARIANT_NOT_FOUND}, as if it did not exist; a stock variant, a blank or an unknown code is refused naming
     * the lines, on {@code lines[i].sku}.
     */
    private void requireOwnSkus(UUID customerId, List<String> skus) {
        customerSkus.requireVisibleTo(skus, customerId);
        Map<String, UUID> owners = customerSkus.ownersOf(skus);
        List<FieldError> errors = new ArrayList<>();
        for (int i = 0; i < skus.size(); i++) {
            if (!customerId.equals(owners.get(skus.get(i)))) {
                errors.add(new FieldError("lines[" + i + "].sku", "%s is not a SKU of this customer"
                        .formatted(skus.get(i)), "CUSTOMER_SKU_REQUIRED"));
            }
        }
        if (!errors.isEmpty()) {
            throw new BusinessException(ErrorCode.CUSTOMER_SKU_REQUIRED, "A quote line is one of the customer's own "
                    + "SKUs: " + String.join(", ", errors.stream().map(FieldError::message).toList()), errors);
        }
    }

    /**
     * A quote that is sent, accepted or converted holds cartons of the customer's own SKUs: not a legacy quote on blanks
     * and designs (QD-16), and every SKU still the customer's (14 BR-04).
     */
    private void requireCustomerSkus(Quote quote, String verb) {
        if (quote.offer().legacy()) {
            throw new BusinessException(ErrorCode.QUOTE_LINES_LEGACY, ("Quote %s was drafted on blanks and designs, "
                    + "before the customer's own SKUs: it is not %s. Revise a draft onto the customer's SKUs, or draft "
                    + "a new quote").formatted(quote.quoteNumber(), verb));
        }
        requireOwnSkus(quote.customerId(), quote.offer().lines().stream().map(line -> line.sku().code()).toList());
    }

    /**
     * The carton unit of each SKU (kltn-docs 01 BR-03, BR-11): its one active packing unit of more than one piece - the
     * default shipping one should an older SKU have several. None, or several none of which is the default shipping
     * unit, is refused: the SKU is sold by the carton and nothing is guessed.
     */
    private Map<String, UomConversion> cartonUnits(List<String> skus) {
        Map<String, List<UomConversion>> declared = units.conversionsOf(skus.stream().distinct().map(Sku::new).toList());
        Map<String, UomConversion> cartons = new java.util.HashMap<>();
        List<String> missing = new ArrayList<>();
        for (String sku : skus.stream().distinct().toList()) {
            List<UomConversion> candidates = declared.getOrDefault(sku, List.of()).stream()
                    .filter(unit -> unit.active() && !unit.base() && unit.factor() > 1).toList();
            if (candidates.size() > 1) {
                candidates = candidates.stream().filter(UomConversion::defaultShipping).toList();
            }
            if (candidates.size() == 1) {
                cartons.put(sku, candidates.getFirst());
            } else {
                missing.add(sku);
            }
        }
        if (!missing.isEmpty()) {
            throw new BusinessException(ErrorCode.CARTON_SPEC_MISSING, ("%s has no single carton unit (01 BR-03): set "
                    + "its carton specification").formatted(String.join(", ", missing)));
        }
        return cartons;
    }

    /**
     * kltn-docs 14 step 2: a line's price starts from the customer's own price for the SKU (13 BR-05), read at the
     * draft's cartons of it. Copied into the quote, so a later price change does not move a quote while it is valid
     * (14 BR-06). Sales may enter any valid tiers instead: no discount limit and no internal approval (user decision
     * 2026-10-12, overriding 14 BR-10); the customer accepts.
     *
     * <p>No price of the customer's own, or the cartons below its first tier: Sales negotiates and enters the line's
     * tiers - the first tier's price is never applied to fewer cartons, and the refusal says so instead of reading as a
     * quote MOQ the customer never saw (user decision P4).</p>
     */
    private List<PriceTier> defaultTiers(UUID customerId, Sku sku, int cartons) {
        PriceSchedule schedule = ownPrice(customerId, sku.code()).orElseThrow(() -> new BusinessException(
                ErrorCode.VALIDATION_FAILED, "The customer has no price of its own for " + sku.code()
                        + "; enter the line's price tiers"));
        if (cartons < schedule.moq()) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "%d cartons of %s is below the customer's price minimum of %d cartons; enter the negotiated price "
                            .formatted(cartons, sku.code(), schedule.moq()) + "tiers");
        }
        return schedule.tiers().stream()
                .map(t -> PriceTier.of(t.minQuantity(), t.unitPrice().amount())).toList();
    }

    /**
     * Tells Sales that the customer's step made the next one theirs. Published in this transaction and
     * delivered after commit to the Sales account that sent the quote, which the notification module resolves when
     * it delivers (gone, inactive or without e-mail: the active Sales team takes over, N1). Ids only in the stored
     * event: no customer name, no e-mail address.
     */
    private void notifySales(Quote quote, CustomerActedOnQuote.Action action, String sourceOrderNumber, UUID salesUserId,
                             Instant at) {
        events.publishEvent(new CustomerActedOnQuote(quote.id(), quote.quoteNumber(), quote.customerId(), action,
                sourceOrderNumber, salesUserId, at));
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
        document.put("requestedDeliveryDate",
                offer.requestedDeliveryDate() == null ? null : offer.requestedDeliveryDate().toString());
        document.put("lines", offer.lines().stream().map(line -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("lineNo", line.lineNo());
            if (line.legacy()) {
                row.put("blankSku", line.sku().code());
                row.put("designSnapshotId", line.designSnapshotId().toString());
                row.put("printSpec", line.printSpec());
                row.put("quantity", line.quantity());
            } else {
                row.put("sku", line.sku().code());
                row.put("uomConversionId", line.uomConversionId().toString());
                row.put("cartons", line.quantity());
                row.put("unitsPerCarton", line.unitsPerCarton());
            }
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
        return view(quote, false);
    }

    /**
     * @param forCustomer read by the customer (portal): a revision Sales reopened and then dropped before sending it
     *                    was never offered - the customer sees the quote end with the offer
     *                    they were last sent, as its SENT history row kept it, not the draft's working terms
     */
    private QuoteView view(Quote quote, boolean forCustomer) {
        Instant now = clock.instant();
        QuoteSearch.Row row = search.findRow(quote.id()).orElseThrow();
        List<QuoteSearch.Transition> rows = search.history(quote.id());
        List<Transition> history = rows.stream()
                .map(t -> new Transition(t.revision(), t.fromStatus(), t.toStatus(), t.reason(), t.changedAt(),
                        t.changedBy()))
                .toList();
        Quote.Offer offer = quote.offer();
        int revision = quote.revision();
        boolean neverOffered = forCustomer && quote.status() == QuoteStatus.CANCELLED && rows.stream()
                .filter(t -> t.toStatus() == QuoteStatus.CANCELLED).reduce((first, last) -> last)
                .map(t -> t.fromStatus() == QuoteStatus.DRAFT).orElse(false);
        Optional<QuoteSearch.Transition> lastSent = rows.stream()
                .filter(t -> t.toStatus() == QuoteStatus.SENT && t.offer() != null).reduce((first, last) -> last);
        if (neverOffered && lastSent.isPresent()) {
            offer = sentOffer(quote, lastSent.get().offer());
            revision = lastSent.get().revision();
        }
        BigDecimal acceptedTotal = null;
        boolean priced = offer.priced();
        BigDecimal proposedTotal = priced ? BigDecimal.ZERO : null;
        List<LineView> lines = new ArrayList<>();
        for (QuoteLine line : offer.lines()) {
            BigDecimal lineTotal = line.acceptedQuantity() == null ? null
                    : line.total(line.acceptedQuantity(), line.acceptedUnitPrice()).amount();
            if (lineTotal != null) {
                acceptedTotal = acceptedTotal == null ? lineTotal : acceptedTotal.add(lineTotal);
            }
            com.stockflow.common.domain.Money proposed = !line.priced() ? null
                    : line.priceFor(offer.proposedQuantity(line.sku().code()));
            BigDecimal proposedLineTotal = proposed == null ? null : line.total(line.quantity(), proposed).amount();
            if (priced) {
                proposedTotal = proposedTotal.add(proposedLineTotal);
            }
            lines.add(new LineView(line.id(), line.lineNo(), line.measure().name(), line.sku().code(),
                    line.legacy() ? null : line.quantity(), line.legacy() ? null : line.unitsPerCarton(), line.units(),
                    line.designSnapshotId(), line.printSpec(), line.priced(), line.priced() ? line.moq() : null,
                    line.tiers().stream().map(t -> new Tier(t.minQuantity(), t.unitPrice().amount())).toList(),
                    proposed == null ? null : proposed.amount(), proposedLineTotal,
                    line.legacy() ? null : line.acceptedQuantity(), line.acceptedUnits(),
                    line.acceptedUnitPrice() == null ? null : line.acceptedUnitPrice().amount(), lineTotal));
        }
        ConvertedOrder converted = quote.convertedOrderId() == null ? null : orders.findById(quote.convertedOrderId())
                .map(o -> new ConvertedOrder(o.orderId(), o.orderNumber(), o.status(), o.total().amount()))
                .orElse(null);
        return new QuoteView(quote.id(), quote.quoteNumber(), quote.customerId(), quote.statusAt(now), revision,
                offer.paymentTerm(), offer.depositPercent(), offer.validUntil(), offer.terms(),
                offer.requestedDeliveryDate(), offer.legacy(), lines, priced,
                proposedTotal,
                acceptedTotal,
                quote.sentAt(), quote.sentBy(), quote.respondedAt(), quote.respondedBy(), quote.customerFeedback(),
                quote.cancellationReason(), converted, quote.convertedAt(), history, row.createdAt(), row.createdBy(),
                row.version());
    }

    /** The offer as {@link #offerJson} kept it when it was sent; its lines keep their ids where the quote still has them. */
    private Quote.Offer sentOffer(Quote quote, String document) {
        try {
            JsonNode sent = json.readTree(document);
            Map<Integer, UUID> ids = new LinkedHashMap<>();
            quote.offer().lines().forEach(line -> ids.put(line.lineNo(), line.id()));
            List<QuoteLine> lines = new ArrayList<>();
            for (JsonNode line : sent.path("lines")) {
                int lineNo = line.path("lineNo").asInt();
                List<PriceTier> tiers = new ArrayList<>();
                for (JsonNode tier : line.path("tiers")) {
                    tiers.add(PriceTier.of(tier.path("minQuantity").asInt(), tier.path("unitPrice").decimalValue()));
                }
                UUID id = ids.getOrDefault(lineNo, UUID.nameUUIDFromBytes(("quote-line:" + quote.id() + ":" + lineNo)
                        .getBytes(StandardCharsets.UTF_8)));
                // A carton line names its SKU; a line sent before 4B-2 its blank and design (QD-16).
                lines.add(line.path("sku").isTextual()
                        ? QuoteLine.carton(id, lineNo, new Sku(line.path("sku").asText()),
                                UUID.fromString(line.path("uomConversionId").asText()),
                                line.path("unitsPerCarton").asInt(), line.path("cartons").asInt(), tiers, null, null)
                        : QuoteLine.legacy(id, lineNo, new Sku(line.path("blankSku").asText()),
                                UUID.fromString(line.path("designSnapshotId").asText()),
                                textOrNull(line.path("printSpec")), line.path("quantity").asInt(), tiers, null, null));
            }
            JsonNode deposit = sent.path("depositPercent");
            return new Quote.Offer(com.stockflow.order.api.PaymentTerm.valueOf(sent.path("paymentTerm").asText()),
                    deposit.isNumber() ? deposit.decimalValue() : null, Instant.parse(sent.path("validUntil").asText()),
                    textOrNull(sent.path("terms")),
                    sent.path("requestedDeliveryDate").isTextual()
                            ? LocalDate.parse(sent.path("requestedDeliveryDate").asText()) : null,
                    lines);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Unreadable offer on the history of quote " + quote.quoteNumber(), e);
        }
    }

    private static String textOrNull(JsonNode node) {
        return node.isTextual() ? node.asText() : null;
    }

    private LocalDate today() {
        return LocalDate.ofInstant(clock.instant(), BUSINESS_ZONE);
    }
}
