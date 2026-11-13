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
 * the quote again - nothing is substituted. Orders already placed carry on whatever happens to the product.</p>
 *
 * <p><b>The customer's own SKUs, by the carton</b> (4B-2; kltn-docs 14 BR-04, BR-07, BR-08, 13 BR-02): drafting,
 * revising, sending, accepting and converting each ask again that every SKU on the quote is one of this customer's own
 * - another customer's answers as if it did not exist, a stock variant or blank is refused - and a line is written in
 * the SKU's one carton unit, its pieces per carton fixed on the line. A line starts from the customer's own price for
 * the SKU, read at the quote's cartons of it; Sales enters the tiers when there is none. A quote drafted before on
 * blanks and designs is kept to read (QD-16): it is not sent, accepted or converted, and an accepted one may be
 * cancelled for that alone.</p>
 *
 * <p><b>VAT and fee lines</b> (4B-3; kltn-docs 14 BR-09, BR-17, BR-20, BR-21): every line copies its product's tax
 * class and that class's rate with its price; a SKU produced for the first time gets a plate-fee line the system adds
 * and Sales prices; Sales may add one shipping fee (STANDARD rate, D31). A quote is sent once every line is priced and
 * taxed and every fee line has its amount. The totals - goods, fees, VAT, the total with it - are read line by line,
 * each line's VAT rounded to the dong, and the order the quote becomes keeps all of it.</p>
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
    private final com.stockflow.order.internal.domain.TaxRates taxRates;
    private final com.stockflow.order.internal.domain.SkuProductionHistory production;

    QuoteService(QuoteRepository quotes, QuoteSearch search, CustomerService customers,
                 UomConversionService units, QuotedOrders quotedOrders, OrderService orders, ObjectMapper json,
                 Clock clock, CatalogService catalog, ApplicationEventPublisher events, ProductService products,
                 com.stockflow.product.api.CustomerSkus customerSkus,
                 com.stockflow.order.internal.domain.TaxRates taxRates,
                 com.stockflow.order.internal.domain.SkuProductionHistory production) {
        this.customerSkus = customerSkus;
        this.taxRates = taxRates;
        this.production = production;
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
        requireActiveSkus(null, offer);
        Quote quote = Quote.draft(Identifiers.newId(), quotes.nextNumber(today()), command.requestId(), customerId, offer);
        quotes.save(quote);
        transition(quote,1, null, QuoteStatus.DRAFT, null, null, clock.instant(), user.userId());
        log.info("Quote {} drafted for customer {} ({} line(s))", quote.quoteNumber(), customerId, offer.lines().size());
        return view(quote);
    }

    @Override
    @Transactional(readOnly = true)
    public PageResponse<Row> list(CurrentUser user, Criteria criteria) {
        Optional<UUID> own = portalCustomer(user);
        Instant now = clock.instant();
        var page = search.search(new QuoteSearch.Criteria(own.orElse(criteria.customerId()), criteria.statuses(),
                criteria.search(), own.isPresent(), now), Pages.of(criteria.page(), criteria.size(),
                SORT.parse(criteria.sort())));
        return Pages.toResponse(page.map(r -> new Row(r.id(), r.quoteNumber(), r.customerId(),
                r.status() == QuoteStatus.SENT && !now.isBefore(r.validUntil()) ? QuoteStatus.EXPIRED : r.status(),
                r.revision(), r.validUntil(), r.sentAt(), r.convertedOrderId(), r.createdAt(),
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
        requireActiveSkus(quote.quoteNumber(), quote.offer());
        requirePlateFees(quote);
        QuoteStatus from = quote.status();
        quote.send(user.userId(), clock.instant());
        quotes.save(quote);
        transition(quote,quote.revision(), from, QuoteStatus.SENT, null, offerJson(quote),
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
        if (quote.statusAt(now) == QuoteStatus.EXPIRED) {
            throw new BusinessException(ErrorCode.QUOTE_EXPIRED, "Quote " + quote.quoteNumber() + " has expired");
        }
        requireCustomerSkus(quote, "accepted");
        requireActiveSkus(quote.quoteNumber(), quote.offer());
        QuoteStatus from = quote.status();
        quote.accept(acceptance.cartons(), user.userId(), now);
        quotes.save(quote);
        transition(quote,quote.revision(), from, QuoteStatus.ACCEPTED, null, null, now,
                user.userId());
        notifySales(quote, CustomerActedOnQuote.Action.ACCEPTED, quote.sentBy(), now);
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
        transition(quote,quote.revision(), from, QuoteStatus.CHANGES_REQUESTED,
                quote.customerFeedback(), null, quote.respondedAt(), user.userId());
        // The feedback stays on the quote: the event carries facts, not what the customer typed (B-4).
        notifySales(quote, CustomerActedOnQuote.Action.CHANGES_REQUESTED, quote.sentBy(),
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
                                line.uomConversionId(), line.acceptedQuantity(), line.unitsPerCarton()),
                        line.taxRate()))
                .toList();
        // 4B-3: the fee lines and every rate as the quote fixed them (14 BR-06, BR-21).
        List<PlaceOrderCommand.Fee> fees = quote.offer().fees().stream()
                .map(fee -> new PlaceOrderCommand.Fee(fee.kind(), fee.sku(), fee.amount(), fee.taxRate()))
                .toList();
        OrderSummary order = quotedOrders.placeQuotedOrder(new PlaceOrderCommand(conversionRequestId(quote.id()),
                quote.customerId(), conversion.shippingAddressId(), conversion.billingAddressId(), true, lines, fees));
        Instant now = clock.instant();
        quote.convert(order.orderId(), now);
        quotes.save(quote);
        transition(quote,quote.revision(), QuoteStatus.ACCEPTED, QuoteStatus.CONVERTED,
                order.orderNumber(), null, now, user.userId());
        log.info("Quote {} converted into order {} ({})", quote.quoteNumber(), order.orderNumber(), order.status());
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
                transition(quote,quote.revision(), QuoteStatus.SENT, QuoteStatus.EXPIRED, null,
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
            transition(quote,quote.revision(), from, quote.status(), reason, null, clock.instant(),
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

    /**
     * The customer's own price for one of its SKUs (13 BR-05: tiers by cartons, a price per piece), if it has one - the
     * only price there is (13 BR-04: no base or group price).
     */
    private Optional<PriceSchedule> ownPrice(UUID customerId, String sku) {
        return catalog.customerPrice(customerId, sku);
    }

    /**
     * Builds and verifies the offer: every line is cartons of one of this customer's own SKUs, written in the SKU's
     * carton unit at its factor now (14 BR-08), its omitted tiers filled from the customer's own price. An edit replaces
     * the lines (new ids); nothing was accepted on a draft.
     */
    private Quote.Offer offer(UUID customerId, Draft draft) {
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
        // 14 BR-21: each line's VAT follows its product's tax class, copied with the price; a product without one
        // leaves the line without a rate, and the quote is not sent until it has one.
        Map<String, com.stockflow.product.api.TaxClass> classes = products.taxClassesOf(skus);
        Map<String, BigDecimal> rates = taxRates.all();
        List<QuoteLine> lines = new ArrayList<>();
        int lineNo = 1;
        for (Line line : draft.lines()) {
            Sku sku = new Sku(line.sku());
            UomConversion carton = cartonUnits.get(sku.code());
            List<PriceTier> tiers = line.tiers() != null && !line.tiers().isEmpty()
                    ? line.tiers().stream().map(t -> PriceTier.of(t.minQuantity(), t.unitPrice())).toList()
                    : line.unpricedWithoutOwnPrice() ? ownTiersOrNone(customerId, sku, cartonsBySku.get(sku.code()))
                    : defaultTiers(customerId, sku, cartonsBySku.get(sku.code()));
            String taxClass = classes.containsKey(sku.code()) ? classes.get(sku.code()).name() : null;
            lines.add(QuoteLine.carton(Identifiers.newId(), lineNo++, sku, carton.id(), carton.factor(), line.cartons(),
                    tiers, null, null, taxClass, taxClass == null ? null : rates.get(taxClass)));
        }
        List<com.stockflow.order.internal.domain.QuoteFee> fees = fees(draft.fees(), lines, classes, rates);
        if (draft.requestedDeliveryDate() != null && draft.requestedDeliveryDate().isBefore(today())) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "The requested delivery date cannot be in the past");
        }
        return new Quote.Offer(draft.validUntil().truncatedTo(java.time.temporal.ChronoUnit.MICROS), draft.terms(),
                draft.requestedDeliveryDate(), lines, fees);
    }

    /**
     * A SKU still produced for the first time when the quote is sent has its plate fee (14 BR-17): the draft got one when
     * it was last edited, but an order of the SKU may have been cancelled since, or the draft may predate fee lines.
     * Sales edits the draft, which adds it, and prices or waives it.
     */
    private void requirePlateFees(Quote quote) {
        if (quote.offer().legacy()) {
            return;
        }
        List<String> skus = quote.offer().lines().stream().map(line -> line.sku().code()).distinct().toList();
        java.util.Set<String> produced = production.producedBefore(skus);
        java.util.Set<String> withFee = quote.offer().fees().stream()
                .filter(fee -> fee.kind() == com.stockflow.order.api.FeeKind.PLATE_FEE)
                .map(fee -> fee.sku().code()).collect(java.util.stream.Collectors.toSet());
        List<String> missing = skus.stream().filter(sku -> !produced.contains(sku) && !withFee.contains(sku)).sorted()
                .toList();
        if (!missing.isEmpty()) {
            throw new BusinessException(ErrorCode.QUOTE_PLATE_FEE_MISSING, ("Quote %s: %s produced for the first time "
                    + "without a plate fee; edit the quote to add it").formatted(quote.quoteNumber(), missing));
        }
    }

    /**
     * The fee lines of a draft (14 BR-17, BR-20): a plate fee for each SKU on it produced for the first time - added by
     * the system, priced by Sales (its amount from the draft when Sales gave one) - and the shipping fee Sales added, at
     * most one. A plate fee for a SKU produced before, or not on the quote, is refused rather than dropped.
     */
    private List<com.stockflow.order.internal.domain.QuoteFee> fees(List<Fee> given, List<QuoteLine> lines,
                                                                     Map<String, com.stockflow.product.api.TaxClass> classes,
                                                                     Map<String, BigDecimal> rates) {
        List<String> skus = lines.stream().map(line -> line.sku().code()).distinct().toList();
        java.util.Set<String> produced = production.producedBefore(skus);
        Map<String, com.stockflow.common.domain.Money> plates = new LinkedHashMap<>();
        com.stockflow.common.domain.Money shipping = null;
        List<FieldError> errors = new ArrayList<>();
        for (int i = 0; i < given.size(); i++) {
            Fee fee = given.get(i);
            String field = "fees[" + i + "]";
            if (fee == null || fee.kind() == null) {
                errors.add(new FieldError(field + ".kind", "PLATE_FEE or SHIPPING_FEE", "NotNull"));
                continue;
            }
            if (fee.kind() == com.stockflow.order.api.FeeKind.SHIPPING_FEE) {
                if (shipping != null || fee.amount() == null) {
                    errors.add(new FieldError(field, "one shipping fee at most, with its amount", "SHIPPING_FEE"));
                    continue;
                }
                shipping = feeAmount(field, fee.amount(), errors);
                continue;
            }
            String sku = fee.sku() == null ? null : new Sku(fee.sku()).code();
            if (sku == null || !skus.contains(sku)) {
                errors.add(new FieldError(field + ".sku", "a plate fee is for a SKU on the quote", "PLATE_FEE"));
            } else if (produced.contains(sku)) {
                errors.add(new FieldError(field + ".sku", "%s was produced before: a reorder has no plate fee (14 BR-17)"
                        .formatted(sku), "PLATE_FEE"));
            } else if (plates.containsKey(sku)) {
                errors.add(new FieldError(field + ".sku", "one plate fee per SKU", "PLATE_FEE"));
            } else {
                plates.put(sku, fee.amount() == null ? null : feeAmount(field, fee.amount(), errors));
            }
        }
        if (!errors.isEmpty()) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "Invalid fee lines", errors);
        }
        List<com.stockflow.order.internal.domain.QuoteFee> fees = new ArrayList<>();
        for (String sku : skus) {
            if (!produced.contains(sku)) {
                String taxClass = classes.containsKey(sku) ? classes.get(sku).name() : null;
                fees.add(new com.stockflow.order.internal.domain.QuoteFee(Identifiers.newId(),
                        com.stockflow.order.api.FeeKind.PLATE_FEE, new Sku(sku), plates.get(sku), taxClass,
                        taxClass == null ? null : rates.get(taxClass)));
            }
        }
        if (shipping != null) {
            String standard = com.stockflow.order.internal.domain.TaxRates.STANDARD;
            fees.add(new com.stockflow.order.internal.domain.QuoteFee(Identifiers.newId(),
                    com.stockflow.order.api.FeeKind.SHIPPING_FEE, null, shipping, standard, rates.get(standard)));
        }
        return fees;
    }

    /** A fee as entered: whole VND, checked before {@code Money} would round 100.5 to 101 without a word. */
    private static com.stockflow.common.domain.Money feeAmount(String field, BigDecimal amount, List<FieldError> errors) {
        if (amount.signum() < 0 || amount.stripTrailingZeros().scale() > 0) {
            errors.add(new FieldError(field + ".amount", "a whole VND amount, 0 or more", "Digits"));
            return null;
        }
        return new com.stockflow.common.domain.Money(amount, com.stockflow.common.domain.Money.VND);
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
            UomConversion.cartonOf(declared.getOrDefault(sku, List.of()))
                    .ifPresentOrElse(carton -> cartons.put(sku, carton), () -> missing.add(sku));
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
    /** The customer's own tiers when they apply at {@code cartons}; none - a line for Sales to price - otherwise. */
    private List<PriceTier> ownTiersOrNone(UUID customerId, Sku sku, int cartons) {
        return ownPrice(customerId, sku.code()).filter(schedule -> cartons >= schedule.moq())
                .map(schedule -> schedule.tiers().stream()
                        .map(t -> PriceTier.of(t.minQuantity(), t.unitPrice().amount())).toList())
                .orElse(List.of());
    }

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
    private void notifySales(Quote quote, CustomerActedOnQuote.Action action, UUID salesUserId, Instant at) {
        events.publishEvent(new CustomerActedOnQuote(quote.id(), quote.quoteNumber(), quote.customerId(), action,
                salesUserId, at));
    }

    /**
     * Records a move of the quote in its history and tells the modules that follow quotes - the chat's quote card above
     * all (kltn-docs 16 §4.3 step 7: the card shows the quote as it is, wherever the customer acted). One place for both,
     * so no move is recorded without being told. Published in this transaction, delivered after commit.
     */
    private void transition(Quote quote, int revision, QuoteStatus from, QuoteStatus to, String reason, String offer,
                            Instant at, UUID by) {
        quotes.recordTransition(quote.id(), revision, from, to, reason, offer, at, by);
        events.publishEvent(new com.stockflow.contracts.QuoteStatusChanged(quote.id(), quote.quoteNumber(),
                quote.customerId(), revision, from == null ? null : from.name(), to.name(), by, at));
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
                row.put("taxClass", line.taxClass());
                row.put("taxRate", line.taxRate());
            }
            row.put("tiers", line.tiers().stream().map(t -> Map.of("minQuantity", t.minQuantity(),
                    "unitPrice", t.unitPrice().amount())).toList());
            return row;
        }).toList());
        document.put("fees", offer.fees().stream().map(fee -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("kind", fee.kind().name());
            row.put("sku", fee.sku() == null ? null : fee.sku().code());
            row.put("amount", fee.amount() == null ? null : fee.amount().amount());
            row.put("taxClass", fee.taxClass());
            row.put("taxRate", fee.taxRate());
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
        boolean priced = offer.priced();
        boolean accepted = !offer.lines().isEmpty() && offer.lines().stream().allMatch(l -> l.acceptedQuantity() != null);
        // 4B-3: a legacy quote predates VAT: its lines' tax is 0 by definition, not unknown. So is an offer sent before
        // 4B-3: a quote is never sent with a line missing its rate since, so a line without one on a quote past DRAFT
        // was offered, accepted and placed without VAT. Only a draft's missing rate is unknown.
        boolean untaxed = offer.legacy() || quote.status() != QuoteStatus.DRAFT
                && offer.lines().stream().anyMatch(line -> line.taxRate() == null);
        TotalsBuilder proposedTotals = new TotalsBuilder();
        TotalsBuilder acceptedTotals = new TotalsBuilder();
        List<LineView> lines = new ArrayList<>();
        for (QuoteLine line : offer.lines()) {
            com.stockflow.common.domain.Money acceptedNet = line.acceptedQuantity() == null ? null
                    : line.total(line.acceptedQuantity(), line.acceptedUnitPrice());
            com.stockflow.common.domain.Money acceptedTax = acceptedNet == null ? null
                    : untaxed ? com.stockflow.common.domain.Money.vnd(0) : line.taxOf(acceptedNet);
            com.stockflow.common.domain.Money proposed = !line.priced() ? null
                    : line.priceFor(offer.proposedQuantity(line.sku().code()));
            com.stockflow.common.domain.Money proposedNet = proposed == null ? null : line.total(line.quantity(), proposed);
            com.stockflow.common.domain.Money proposedTax = proposedNet == null ? null
                    : untaxed ? com.stockflow.common.domain.Money.vnd(0) : line.taxOf(proposedNet);
            proposedTotals.goods(proposedNet, proposedTax);
            acceptedTotals.goods(acceptedNet, acceptedTax);
            lines.add(new LineView(line.id(), line.lineNo(), line.measure().name(), line.sku().code(),
                    line.legacy() ? null : line.quantity(), line.legacy() ? null : line.unitsPerCarton(), line.units(),
                    line.designSnapshotId(), line.printSpec(), line.priced(), line.priced() ? line.moq() : null,
                    line.tiers().stream().map(t -> new Tier(t.minQuantity(), t.unitPrice().amount())).toList(),
                    proposed == null ? null : proposed.amount(), amount(proposedNet),
                    line.legacy() ? null : line.acceptedQuantity(), line.acceptedUnits(),
                    line.acceptedUnitPrice() == null ? null : line.acceptedUnitPrice().amount(), amount(acceptedNet),
                    line.taxClass(), line.taxRate(), amount(proposedTax), amount(acceptedTax)));
        }
        List<FeeView> fees = new ArrayList<>();
        for (com.stockflow.order.internal.domain.QuoteFee fee : offer.fees()) {
            proposedTotals.fee(fee.amount(), fee.tax());
            acceptedTotals.fee(fee.amount(), fee.tax());
            fees.add(new FeeView(fee.id(), fee.kind(), fee.sku() == null ? null : fee.sku().code(), amount(fee.amount()),
                    fee.taxClass(), fee.taxRate(), amount(fee.tax())));
        }
        Totals proposedView = proposedTotals.build();
        Totals acceptedView = accepted ? acceptedTotals.build() : null;
        ConvertedOrder converted = quote.convertedOrderId() == null ? null : orders.findById(quote.convertedOrderId())
                // 17 §5.2: the customer meets the order once its contract is sent, not while it is drafted.
                .filter(o -> !forCustomer || o.status() != com.stockflow.order.api.OrderStatus.DRAFT)
                .map(o -> new ConvertedOrder(o.orderId(), o.orderNumber(), o.status(), o.total().amount()))
                .orElse(null);
        return new QuoteView(quote.id(), quote.quoteNumber(), quote.customerId(), quote.statusAt(now), revision,
                offer.validUntil(), offer.terms(),
                offer.requestedDeliveryDate(), offer.legacy(), lines, fees, priced, proposedView, acceptedView,
                proposedView.total(),
                acceptedView == null ? null : acceptedView.total(),
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
                                line.path("unitsPerCarton").asInt(), line.path("cartons").asInt(), tiers, null, null,
                                textOrNull(line.path("taxClass")),
                                line.path("taxRate").isNumber() ? line.path("taxRate").decimalValue() : null)
                        : QuoteLine.legacy(id, lineNo, new Sku(line.path("blankSku").asText()),
                                UUID.fromString(line.path("designSnapshotId").asText()),
                                textOrNull(line.path("printSpec")), line.path("quantity").asInt(), tiers, null, null));
            }
            // 4B-3: the fee lines as sent; an offer sent before had none.
            List<com.stockflow.order.internal.domain.QuoteFee> fees = new ArrayList<>();
            for (JsonNode fee : sent.path("fees")) {
                fees.add(new com.stockflow.order.internal.domain.QuoteFee(UUID.nameUUIDFromBytes(("quote-fee:"
                        + quote.id() + ":" + fee.path("kind").asText() + ":" + fee.path("sku").asText())
                        .getBytes(StandardCharsets.UTF_8)),
                        com.stockflow.order.api.FeeKind.valueOf(fee.path("kind").asText()),
                        fee.path("sku").isTextual() ? new Sku(fee.path("sku").asText()) : null,
                        fee.path("amount").isNumber() ? new com.stockflow.common.domain.Money(
                                fee.path("amount").decimalValue(), com.stockflow.common.domain.Money.VND) : null,
                        textOrNull(fee.path("taxClass")),
                        fee.path("taxRate").isNumber() ? fee.path("taxRate").decimalValue() : null));
            }
            // An offer sent before 4B-10 also kept its paymentTerm and depositPercent: no longer read.
            return new Quote.Offer(Instant.parse(sent.path("validUntil").asText()),
                    textOrNull(sent.path("terms")),
                    sent.path("requestedDeliveryDate").isTextual()
                            ? LocalDate.parse(sent.path("requestedDeliveryDate").asText()) : null,
                    lines, fees);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Unreadable offer on the history of quote " + quote.quoteNumber(), e);
        }
    }

    private static BigDecimal amount(com.stockflow.common.domain.Money money) {
        return money == null ? null : money.amount();
    }

    /**
     * Adds an offer up line by line (14 BR-09): the goods, the fees, the VAT of each - a part left unknown (a line not
     * priced, a fee not priced, a rate missing) leaves that part and the total null.
     */
    private static final class TotalsBuilder {
        private BigDecimal subtotal = BigDecimal.ZERO;
        private BigDecimal fees = BigDecimal.ZERO;
        private BigDecimal tax = BigDecimal.ZERO;

        void goods(com.stockflow.common.domain.Money net, com.stockflow.common.domain.Money vat) {
            subtotal = add(subtotal, net);
            tax = add(tax, vat);
        }

        void fee(com.stockflow.common.domain.Money amount, com.stockflow.common.domain.Money vat) {
            fees = add(fees, amount);
            tax = add(tax, vat);
        }

        private static BigDecimal add(BigDecimal sum, com.stockflow.common.domain.Money part) {
            return sum == null || part == null ? null : sum.add(part.amount());
        }

        Totals build() {
            BigDecimal total = subtotal == null || fees == null || tax == null ? null : subtotal.add(fees).add(tax);
            return new Totals(subtotal, fees, tax, total);
        }
    }

    private static String textOrNull(JsonNode node) {
        return node.isTextual() ? node.asText() : null;
    }

    private LocalDate today() {
        return LocalDate.ofInstant(clock.instant(), BUSINESS_ZONE);
    }
}
