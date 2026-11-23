package com.stockflow.invoicing.internal.service;

import com.stockflow.common.audit.AuditAction;
import com.stockflow.common.audit.Auditable;
import com.stockflow.common.config.SellerProperties;
import com.stockflow.common.domain.BusinessCalendar;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.id.Identifiers;
import com.stockflow.common.logging.LogSafe;
import com.stockflow.contracts.EInvoiceBuyerIncomplete;
import com.stockflow.contracts.EInvoiceNeedsAttention;
import com.stockflow.customer.api.CustomerLegalProfile;
import com.stockflow.customer.api.CustomerLegalProfiles;
import com.stockflow.customer.api.CustomerService;
import com.stockflow.customer.api.CustomerSummary;
import com.stockflow.invoicing.internal.domain.CorrectionBasis;
import com.stockflow.invoicing.internal.domain.EInvoice;
import com.stockflow.invoicing.internal.domain.EInvoiceLine;
import com.stockflow.invoicing.internal.domain.EInvoiceProvider;
import com.stockflow.invoicing.internal.domain.EInvoiceRepository;
import com.stockflow.invoicing.internal.domain.EInvoiceStatus;
import com.stockflow.invoicing.internal.domain.EInvoiceType;
import com.stockflow.invoicing.internal.domain.FeeAllocation;
import com.stockflow.invoicing.internal.domain.InvoiceParty;
import com.stockflow.order.api.DeliveredShipment;
import com.stockflow.order.api.DeliveredShipments;
import com.stockflow.order.api.FeeKind;
import com.stockflow.order.api.OrderShippingFee;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.ResourceBundle;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Draws up the draft invoice of a delivered shipment and takes the accountant's corrections to it (plan §1.4;
 * kltn-docs 21 §4.1, §4.5).
 *
 * <h2>What goes on the draft</h2>
 * <ul>
 *   <li><b>Goods</b>: each order line the shipment delivered, as cartons at the carton's price - the contract's price
 *       of a piece times the pieces in a carton - with the VAT rate of the day of delivery ({@code ordering.tax_rate}).
 *       A line not ordered in cartons is counted in pieces.</li>
 *   <li><b>Fees</b>: each plate fee on the first invoice drawn up for the order, and this delivery's share of the
 *       order's shipping fee - both decided from what the order's invoices already carry ({@link FeeAllocation}).</li>
 *   <li><b>The parties, copied</b>: the seller from this deployment's configuration, the buyer from the customer's
 *       legal profile - name, tax code, registered address, invoice e-mail. A buyer with something missing still gets
 *       its draft, flagged; it is issuing that is refused.</li>
 * </ul>
 *
 * <p>A shipment has one original invoice in force - an original, or the replacement standing for it, reversed because
 * its delivery was corrected to a failure no longer counts, so a shipment delivered again is drawn up anew (WF-05
 * HĐ-3). Asking again for a shipment that has one returns it. Drafts of one
 * order are drawn up one after the other, under the order's invoicing lock: the second sees the first, and the fees it
 * carries. {@code uk_einvoice_original_per_shipment} is the last line of defence.</p>
 */
@Service
public class EInvoiceDrafts {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(EInvoiceDrafts.class);

    /** The outcome of asking for a shipment's draft: the invoice, and whether this call drew it up. */
    public record DrawnUp(EInvoice.Snapshot invoice, boolean created) {
    }

    /**
     * The accountant's corrections to a draft, against the version read. A null field leaves the buyer's value as it
     * is; {@code lineNames} renames lines by id.
     */
    public record Revise(UUID id, long expectedVersion, String buyerName, String buyerTaxCode, String buyerAddress,
                         String buyerEmail, Map<UUID, String> lineNames) {
    }

    /** kltn-docs 18 BR-01, {@code ck_einvoice_buyer_tax_code}: 10 digits, 10-3 for a dependent unit, or 12 for a household. */
    private static final Pattern TAX_CODE = Pattern.compile("\\d{10}(-\\d{3})?|\\d{12}");
    private static final Pattern EMAIL = Pattern.compile("[^@\\s]+@[^@\\s]+\\.[^@\\s]+");

    private final EInvoiceRepository invoices;
    private final DeliveredShipments shipments;
    private final CustomerLegalProfiles legalProfiles;
    private final CustomerService customers;
    private final SellerProperties seller;
    private final ApplicationEventPublisher events;
    private final Clock clock;
    private final EInvoiceProviders providers;

    EInvoiceDrafts(EInvoiceRepository invoices, DeliveredShipments shipments, CustomerLegalProfiles legalProfiles,
                   CustomerService customers, SellerProperties seller, ApplicationEventPublisher events, Clock clock,
                   EInvoiceProviders providers) {
        this.invoices = invoices;
        this.shipments = shipments;
        this.legalProfiles = legalProfiles;
        this.customers = customers;
        this.seller = seller;
        this.events = events;
        this.clock = clock;
        this.providers = providers;
    }

    /**
     * The original invoice of a delivered shipment: the one in force if there is one, else a new draft.
     *
     * <p><b>In a transaction of its own</b> ({@code REQUIRES_NEW}), whoever calls. The listener that hears of a
     * delivery runs in its own transaction and must end normally when a delivery has nothing to invoice; an exception
     * leaving a method that had joined that transaction would mark it rollback-only, the listener's event would never
     * be completed, and it would be delivered and refused again at every restart. Here a refusal rolls back this
     * transaction alone.</p>
     *
     * <p>Under the order's invoicing lock, taken before anything is read: two drafts of one order see each other's
     * fees, and a delivery corrected to a failure meanwhile is either seen here (nothing is drawn up) or finds this
     * draft when it comes to void it ({@link #voidForCorrectedDelivery}).</p>
     *
     * <p><b>Dated the day of the delivery, never a guess.</b> The invoice's date is the day the goods passed to the
     * buyer (NĐ 254 Điều 9 khoản 1). A delivered shipment without a delivery time is refused rather than dated today:
     * today is a date the law does not allow once the delivery is a day old, and an invoice is never re-dated once it is
     * issued. The refusal ({@code EINVOICE_DELIVERY_TIME_MISSING}) reaches the accountants as an
     * {@code EInvoiceDraftFailed}; the draft is drawn up by hand once the delivery's time is recorded.</p>
     *
     * @throws BusinessException {@code EINVOICE_NOT_INVOICEABLE} when the shipment is not delivered (or its delivery
     *         was corrected to a failure), delivered nothing, is not priced in dong, or had its invoice replaced;
     *         {@code EINVOICE_DELIVERY_TIME_MISSING} when it has no delivery time;
     *         {@code SELLER_PROFILE_UNAVAILABLE} while the seller is not configured
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public DrawnUp drawUp(UUID orderId, UUID shipmentId) {
        invoices.lockOrder(orderId);
        Optional<EInvoice> inForce = invoices.findLiveOriginalOfShipment(shipmentId);
        if (inForce.isPresent()) {
            return new DrawnUp(inForce.get().snapshot(), false);
        }
        if (invoices.hasReplacedOriginalOfShipment(shipmentId)) {
            // What a shipment whose invoice was replaced is invoiced for is its replacement's business (E4): an error
            // is corrected on the replacement, never by a second original beside it.
            throw new BusinessException(ErrorCode.EINVOICE_NOT_INVOICEABLE,
                    "The invoice of shipment %s was replaced; it is not drawn up again".formatted(shipmentId));
        }
        DeliveredShipment delivered = shipments.find(orderId, shipmentId)
                .orElseThrow(() -> new BusinessException(ErrorCode.EINVOICE_NOT_INVOICEABLE,
                        "Shipment %s of order %s is not delivered".formatted(shipmentId, orderId)));
        if (!EInvoice.VND.equals(delivered.currency())) {
            throw new BusinessException(ErrorCode.EINVOICE_NOT_INVOICEABLE,
                    "Order %s is priced in %s; a VAT invoice is in dong".formatted(delivered.orderNumber(),
                            delivered.currency()));
        }
        if (!seller.complete()) {
            throw new BusinessException(ErrorCode.SELLER_PROFILE_UNAVAILABLE,
                    "stockflow.seller.* is not configured in this deployment");
        }
        Lines lines = lines(delivered, invoices.invoicedOfOrder(orderId));
        if (lines.lines().isEmpty()) {
            throw new BusinessException(ErrorCode.EINVOICE_NOT_INVOICEABLE,
                    "Shipment %s delivered nothing to invoice".formatted(shipmentId));
        }
        if (delivered.deliveredAt() == null) {
            throw new BusinessException(ErrorCode.EINVOICE_DELIVERY_TIME_MISSING,
                    "Shipment %s of order %s has no delivery time to date its invoice by".formatted(
                            delivered.shipmentCode(), delivered.orderNumber()));
        }
        LocalDate deliveredOn = BusinessCalendar.date(delivered.deliveredAt());
        EInvoice invoice = EInvoice.drawUpOriginal(Identifiers.newId(), invoices.nextCode(deliveredOn),
                new EInvoice.Delivery(delivered.orderId(), delivered.orderNumber(), delivered.contractNumber(),
                        delivered.shipmentId(), delivered.shipmentCode(), delivered.deliveryBatchId(),
                        delivered.customerId(), deliveredOn),
                new InvoiceParty(seller.name(), seller.taxCode(), seller.address(), null),
                buyerOf(delivered.customerId()), lines.lines(), lines.rateDiffersFromContract(),
                lines.rateNotOnContract(), lines.taxClassUnknown());
        EInvoice.Snapshot drawn = invoices.save(invoice).snapshot();
        announceIncompleteBuyer(drawn);
        return new DrawnUp(drawn, true);
    }

    /**
     * What the rest of an order's fees comes to now, measured by the one rule both the draw-up
     * ({@link #drawUpShippingFeeRest}) and the order's readiness ({@code DeliveryInvoicesAdapter#shippingFeeUnissued})
     * apply, so the order never waits for what the draw-up would not draw up (audit r3 LM-2).
     *
     * @param shipping    the shipping fee, before tax, the order owes and no invoice carries: an invoice of the rest
     * @param plates      the plate fees no invoice of the order carries: on the same invoice (BE-62)
     * @param overCarried the shipping fee, before tax, the order's rest invoices carry beyond what payment now keeps -
     *                    its share was waived after they were issued (Q-19): a reduction of them
     */
    record FeeRest(BigDecimal shipping, List<DeliveredShipment.Fee> plates, BigDecimal overCarried) {

        /** Whether a new rest invoice is drawn up: some shipping fee or a plate fee on no invoice. */
        boolean toDrawUp() {
            return shipping.signum() > 0 || !plates.isEmpty();
        }

        /** Whether anything of the rest is still to invoice or to take off. */
        boolean outstanding() {
            return toDrawUp() || overCarried.signum() > 0;
        }
    }

    /**
     * Within this much, before tax, an order's rest invoices and payment's figure are the same fee (audit r3 LM-2): the
     * deliveries' shares are rounded to the dong one by one and payment rounds its waiver once, so the two may disagree
     * by a dong without anything having happened. Once a rest invoice was issued, a dong either way is not a reason for
     * another invoice of a dong; a first rest invoice takes the rest to the dong, as before.
     */
    static final BigDecimal REST_ROUNDING = BigDecimal.ONE;

    /**
     * {@link FeeRest} from the order's fees and what its invoices carry.
     *
     * @param invoiced      what the order's originals in force carry ({@code invoicedOfOrder}), the rest invoices too
     * @param reducedOnRest the shipping fee lines of the live reductions of the order's rest invoices (at or below 0)
     * @param restShipping  what the order's issued rest invoices still carry of the shipping fee, those reductions taken
     *                      off: the most a reduction may take, and whether the rest was decided before
     */
    static FeeRest feeRest(OrderShippingFee order, FeeAllocation.Invoiced invoiced, BigDecimal reducedOnRest,
                           BigDecimal restShipping) {
        // The deliveries are over for the fees when every piece the order still means to deliver is on an invoice:
        // before, the delivery still to be invoiced carries the plate fee of its SKU and the rest of the shipping fee.
        boolean deliveriesOver = invoiced.units() >= order.orderUnits();
        BigDecimal owed = order.fee() == null ? BigDecimal.ZERO
                : FeeAllocation.shippingFeeOwed(order.fee().amount(), order.shippingFeeWaiver().feeGross(),
                        order.shippingFeeWaiver().waived());
        FeeAllocation.Invoiced net = new FeeAllocation.Invoiced(invoiced.units(),
                invoiced.shippingFee().add(reducedOnRest), invoiced.plateFeeSkus(), invoiced.goodsValue());
        // The share waived for shipments that came back (Q-19, owner 2026-11-23) is owed by nobody and not invoiced;
        // nothing delivered, nothing invoiced, no part of it is owed (FeeAllocation.shippingRest answers 0 then).
        BigDecimal shipping = FeeAllocation.shippingRest(owed, order.orderUnits(), net);
        // BE-62 (Q-19): a plate fee no invoice of the order carries - its goods never reached the customer, so no
        // delivery's invoice took it, or the one that did was reduced to nothing (HĐ-3) - is owed all the same.
        List<DeliveredShipment.Fee> plates = !deliveriesOver ? List.of() : order.plateFees().stream()
                .filter(fee -> fee.amount().signum() > 0 && FeeAllocation.carriesPlateFee(fee.sku(), invoiced))
                .toList();
        BigDecimal overCarried = !deliveriesOver ? BigDecimal.ZERO
                : net.shippingFee().subtract(owed).min(restShipping.max(BigDecimal.ZERO)).max(BigDecimal.ZERO);
        if (restShipping.signum() > 0) {
            shipping = shipping.compareTo(REST_ROUNDING) <= 0 ? BigDecimal.ZERO : shipping;
            overCarried = overCarried.compareTo(REST_ROUNDING) <= 0 ? BigDecimal.ZERO : overCarried;
        }
        return new FeeRest(shipping, plates, overCarried);
    }

    /**
     * The order's issued rest invoices, the latest first, with the shipping fee their live reductions take off each
     * ({@code reducedOf}), those reductions together ({@code reduced}, at or below 0), and what they still carry of the
     * shipping fee ({@code shipping}).
     */
    record RestInvoices(List<EInvoice> issued, Map<UUID, BigDecimal> reducedOf, BigDecimal reduced,
                        BigDecimal shipping) {

        static RestInvoices of(EInvoiceRepository invoices, UUID orderId) {
            List<EInvoice> issued = invoices.findIssuedShippingFeeInvoices(orderId);
            Map<UUID, BigDecimal> reducedOf = new HashMap<>();
            BigDecimal reduced = BigDecimal.ZERO;
            BigDecimal shipping = BigDecimal.ZERO;
            for (EInvoice rest : issued) {
                BigDecimal off = invoices.shippingFeeReducedOf(rest.id());
                off = off == null ? BigDecimal.ZERO : off;
                reducedOf.put(rest.id(), off);
                reduced = reduced.add(off);
                shipping = shipping.add(shippingOn(rest)).add(off);
            }
            return new RestInvoices(List.copyOf(issued), Map.copyOf(reducedOf), reduced, shipping);
        }
    }

    /**
     * {@link #feeRest} of the order as it stands, every rest invoice counted: what the order's readiness asks
     * ({@code DeliveryInvoicesAdapter#shippingFeeUnissued}), measured as {@link #drawUpShippingFeeRest} measures it.
     */
    static FeeRest feeRestOf(EInvoiceRepository invoices, OrderShippingFee order) {
        RestInvoices rests = RestInvoices.of(invoices, order.orderId());
        return feeRest(order, invoices.invoicedOfOrder(order.orderId()), rests.reduced(), rests.shipping());
    }

    /**
     * The invoice of the rest of an order's fees, once its deliveries are over - its undelivered rest closed, or its
     * last shipment come back or lost (WF-05 BE-62, {@code ShipmentReturnSettledListener}): a new draft when some of
     * the shipping fee is on no invoice and no delivery is left to carry it ({@link FeeAllocation#shippingRest}), or a
     * plate fee is on none (Q-19: an order whose goods all came back keeps its plate fee, which only a delivery's invoice
     * ever carried); the one standing when it carries what is owed; empty when there is nothing to draw up.
     *
     * <h2>Decided again, not once (audit r3 LM-2)</h2>
     *
     * <p>The rest was decided once, and the invoice of it returned to every later call. It is not settled once: a
     * delivery corrected to a failure afterwards takes its invoice - and the plate fee it carried - off the order
     * (HĐ-3), and a carrier return afterwards waives the shipping fee the rest invoice carries (Q-19: nothing
     * delivered, the whole fee). The plate fee then stood on no invoice (BE-62 again), the order waited for good on a
     * rest no call would draw up - its contract never liquidated - and the issued rest invoice billed a fee payment no
     * longer collected. So every call measures the rest again ({@link #feeRest}):</p>
     * <ul>
     *   <li>a rest invoice still a draft (or refused) that no longer carries what is owed is voided and drawn up
     *       again; one at the provider is waited for;</li>
     *   <li>a plate fee or shipping fee owed that no invoice carries goes on a new rest invoice - another one beside an
     *       issued rest invoice ({@code uk_einvoice_shipping_fee_rest} now holds one unissued at a time, and
     *       {@code tg_einvoice_rest_plate_once} a plate fee on one invoice);</li>
     *   <li>shipping fee an issued rest invoice carries beyond what payment keeps is taken off it by a reduction
     *       ({@code DELIVERY_FAILED}: the delivery it was charged for did not happen), drawn up for the accountant to
     *       issue - an issued invoice is never changed, and only a reduction states the fee is not owed.</li>
     * </ul>
     *
     * <h2>Why an original of its own, and why dated the day the order's rest was closed</h2>
     *
     * <p>The fee is the price of carrying the order, a service (NĐ 254 Điều 9 khoản 2: its invoice is drawn up when
     * it is done), and what is owed for it is invoiced in full. It was invoiced delivery by delivery, each delivery at
     * its share of the pieces the order then meant to deliver, and the delivery that completes the order takes the
     * rest. Closing the
     * rest of the order after every delivery was invoiced leaves no such delivery, and part of the fee on no invoice.
     * The invoices already issued are not wrong - each was right for what the order was when it was drawn up - so this
     * is not what an adjustment is for (TT 91 Điều 10: errors, returns, discounts), and the missing amount goes on a
     * new original that names no shipment and carries that one fee line.</p>
     *
     * <p>It is dated the day the order's rest was closed - the day this invoice is drawn up - and taxed at that
     * day's VAT rate (owner's decision 13/11/2026 on E0 M5). Until the rest is closed the order still means to
     * deliver it, and the delivery that would complete it is the one to carry the fee; only the closing settles that
     * no delivery will, and how much of the fee was left. Dating it back to the last delivery - which may be weeks
     * earlier - would give a newly drawn invoice a date before invoices already issued: a later number under an
     * earlier date, which NĐ 254 Phụ lục điểm 2 (numbers run in the order of the dates) and the rule that keeps a
     * later-dated invoice back while an earlier one of its series is at the provider
     * ({@code EINVOICE_OLDER_DRAFT_PENDING}) both exist to prevent, and an invoice overdue on the day it is drawn up.</p>
     *
     * <p>In its own transaction ({@code REQUIRES_NEW}) and under the order's invoicing lock, for the reasons
     * {@link #drawUp} gives: a delivery's draft drawn up at the same moment either sees this one's fee line or is seen
     * by it, so the fee is carried once, by whichever is decided second taking only what the first left.</p>
     *
     * @param closedAt when the order's rest was closed: the invoice's date and the day of its VAT rate; now when the
     *                 accountant draws it up by hand
     * @return the new rest invoice, else the new reduction of a rest invoice ({@code created} true), else the rest
     *         invoice standing ({@code created} false); empty when the order has none and nothing is owed
     * @throws BusinessException {@code EINVOICE_NOT_INVOICEABLE} for an order not priced in dong;
     *         {@code SELLER_PROFILE_UNAVAILABLE} while the seller is not configured
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Optional<DrawnUp> drawUpShippingFeeRest(UUID orderId, Instant closedAt) {
        invoices.lockOrder(orderId);
        Optional<EInvoice> unissued = invoices.findUnissuedShippingFeeInvoice(orderId);
        if (unissued.isPresent() && unissued.get().status() == EInvoiceStatus.SUBMITTING) {
            // At the provider: what it carries is decided again once it is answered, by the next call.
            return Optional.of(new DrawnUp(unissued.get().snapshot(), false));
        }
        Optional<OrderShippingFee> found = shipments.orderFees(orderId, closedAt);
        if (found.isEmpty()) {
            return unissued.or(() -> invoices.findLiveShippingFeeInvoice(orderId))
                    .map(standing -> new DrawnUp(standing.snapshot(), false));
        }
        OrderShippingFee order = found.get();
        RestInvoices rests = RestInvoices.of(invoices, orderId);
        // A rest invoice not issued yet is decided again: measured without it, kept only if it carries just that.
        FeeAllocation.Invoiced invoiced = invoices.invoicedOfOrder(orderId);
        if (unissued.isPresent()) {
            invoiced = without(invoiced, unissued.get());
        }
        FeeRest rest = feeRest(order, invoiced, rests.reduced(), rests.shipping());
        if (unissued.isPresent()) {
            EInvoice draft = invoices.findByIdForUpdate(unissued.get().id()).orElseThrow();
            if ((draft.status() != EInvoiceStatus.DRAFT && draft.status() != EInvoiceStatus.REJECTED)
                    || carries(draft, rest)) {
                return Optional.of(new DrawnUp(draft.snapshot(), false));
            }
            voidUnissued(draft, "The rest of the order's fees changed since this invoice was drawn up: drawn up again");
            log.info("E-invoice {} of the rest of the fees of order {} voided: the rest changed since it was drawn up",
                    LogSafe.text(draft.code()), LogSafe.text(order.orderNumber()));
        }
        Optional<EInvoice.Snapshot> reduction = rest.overCarried().signum() > 0
                ? reduceRest(order, rests, rest.overCarried(), closedAt) : Optional.empty();
        if (!rest.toDrawUp()) {
            if (reduction.isPresent()) {
                return Optional.of(new DrawnUp(reduction.get(), true));
            }
            return rests.issued().stream().findFirst().map(standing -> new DrawnUp(standing.snapshot(), false));
        }
        if (!EInvoice.VND.equals(order.currency())) {
            throw new BusinessException(ErrorCode.EINVOICE_NOT_INVOICEABLE,
                    "Order %s is priced in %s; a VAT invoice is in dong".formatted(order.orderNumber(),
                            order.currency()));
        }
        if (!seller.complete()) {
            throw new BusinessException(ErrorCode.SELLER_PROFILE_UNAVAILABLE,
                    "stockflow.seller.* is not configured in this deployment");
        }
        ResourceBundle t = ResourceBundle.getBundle("i18n.messages", Locale.forLanguageTag("vi"));
        List<EInvoiceLine> lines = new ArrayList<>();
        boolean differs = false;
        boolean notOnContract = false;
        boolean classUnknown = false;
        if (rest.shipping().signum() > 0) {
            DeliveredShipment.Fee fee = order.fee();
            lines.add(EInvoiceLine.fee(Identifiers.newId(), 1, EInvoiceLine.FeeKind.SHIPPING_FEE, null,
                    t.getString("einvoice.line.shippingFee.rest"), t.getString("einvoice.unit.fee"), rest.shipping(),
                    fee.taxRate()));
            differs = differs(fee.taxRate(), fee.contractTaxRate());
            notOnContract = fee.contractTaxRate() == null;
        }
        for (DeliveredShipment.Fee plate : rest.plates()) {
            lines.add(EInvoiceLine.fee(Identifiers.newId(), lines.size() + 1, EInvoiceLine.FeeKind.PLATE_FEE,
                    plate.sku(), t.getString("einvoice.line.plateFee").formatted(plate.sku()),
                    t.getString("einvoice.unit.fee"), plate.amount(), plate.taxRate()));
            differs |= differs(plate.taxRate(), plate.contractTaxRate());
            notOnContract |= plate.contractTaxRate() == null;
            classUnknown |= plate.taxClassUnknown();
        }
        Instant completedAt = closedAt != null ? closedAt : clock.instant();
        LocalDate completedOn = BusinessCalendar.date(completedAt);
        EInvoice invoice = EInvoice.drawUpOriginal(Identifiers.newId(), invoices.nextCode(completedOn),
                new EInvoice.Delivery(order.orderId(), order.orderNumber(), order.contractNumber(), null, null, null,
                        order.customerId(), completedOn),
                new InvoiceParty(seller.name(), seller.taxCode(), seller.address(), null),
                buyerOf(order.customerId()), lines, differs, notOnContract, classUnknown);
        EInvoice.Snapshot drawn = invoices.save(invoice).snapshot();
        announceIncompleteBuyer(drawn);
        return Optional.of(new DrawnUp(drawn, true));
    }

    /** The shipping fee, before tax, on an invoice's own lines. */
    private static BigDecimal shippingOn(EInvoice invoice) {
        return invoice.lines().stream().filter(line -> line.feeKind() == EInvoiceLine.FeeKind.SHIPPING_FEE)
                .map(EInvoiceLine::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    /** What the order's invoices carry without {@code rest}, a rest invoice decided again. */
    private static FeeAllocation.Invoiced without(FeeAllocation.Invoiced invoiced, EInvoice rest) {
        java.util.Set<String> plates = new java.util.HashSet<>(invoiced.plateFeeSkus());
        rest.lines().stream().filter(line -> line.feeKind() == EInvoiceLine.FeeKind.PLATE_FEE)
                .forEach(line -> plates.remove(line.sku()));
        return new FeeAllocation.Invoiced(invoiced.units(), invoiced.shippingFee().subtract(shippingOn(rest)), plates,
                invoiced.goodsValue());
    }

    /** Whether a rest invoice not issued yet carries exactly what {@code rest} draws up, and nothing is over-carried. */
    private static boolean carries(EInvoice draft, FeeRest rest) {
        if (rest.overCarried().signum() > 0 || shippingOn(draft).compareTo(rest.shipping()) != 0) {
            return false;
        }
        Map<String, BigDecimal> onDraft = new HashMap<>();
        draft.lines().stream().filter(line -> line.feeKind() == EInvoiceLine.FeeKind.PLATE_FEE)
                .forEach(line -> onDraft.merge(line.sku(), line.amount(), BigDecimal::add));
        Map<String, BigDecimal> owed = new HashMap<>();
        rest.plates().forEach(plate -> owed.merge(plate.sku(), plate.amount().setScale(0, RoundingMode.HALF_UP),
                BigDecimal::add));
        return onDraft.keySet().equals(owed.keySet()) && onDraft.entrySet().stream()
                .allMatch(entry -> entry.getValue().compareTo(owed.get(entry.getKey())) == 0);
    }

    /**
     * Takes {@code over} - shipping fee the order's issued rest invoices carry beyond what payment keeps - off them,
     * the latest first, each by a draft reduction of its shipping fee line ({@code DELIVERY_FAILED}: what it charged
     * for was not delivered, Q-19), dated the day it is drawn up and re-dated to the day it is issued (Q-1). A rest
     * invoice being replaced is not adjusted (Q-9): what is left of {@code over} then is told to the accountants, to
     * settle on the provider's portal.
     *
     * @return the last reduction drawn up; empty when none could be
     */
    private Optional<EInvoice.Snapshot> reduceRest(OrderShippingFee order, RestInvoices rests, BigDecimal over,
                                                   Instant on) {
        List<EInvoice> issuedRests = rests.issued();
        ResourceBundle t = ResourceBundle.getBundle("i18n.messages", Locale.forLanguageTag("vi"));
        LocalDate day = BusinessCalendar.date(on != null ? on : clock.instant());
        BigDecimal left = over;
        Optional<EInvoice.Snapshot> last = Optional.empty();
        for (EInvoice unlocked : issuedRests) {
            if (left.signum() <= 0) {
                break;
            }
            EInvoice rest = invoices.findByIdForUpdate(unlocked.id()).orElseThrow();
            Optional<EInvoiceLine> line = rest.lines().stream()
                    .filter(candidate -> candidate.feeKind() == EInvoiceLine.FeeKind.SHIPPING_FEE).findFirst();
            BigDecimal carried = shippingOn(rest).add(rests.reducedOf().getOrDefault(rest.id(), BigDecimal.ZERO));
            EInvoice.Corrections corrections = invoices.correctionsOf(rest.id());
            if (rest.status() != EInvoiceStatus.ISSUED || line.isEmpty() || carried.signum() <= 0
                    || corrections.liveReplacement()) {
                continue;
            }
            BigDecimal take = left.min(carried);
            EInvoiceLine off = line.get().corrected(Identifiers.newId(), 1, -1, null, line.get().unit(), take,
                    line.get().taxRate(), line.get().itemName());
            EInvoice adjustment = EInvoice.drawUpAdjustment(Identifiers.newId(), invoices.nextCode(day), rest,
                    CorrectionBasis.DELIVERY_FAILED,
                    t.getString("einvoice.correction.shippingFeeWaived").formatted(order.orderNumber()),
                    rest.buyer(), List.of(off), day, null, List.of(), corrections);
            last = Optional.of(invoices.save(adjustment).snapshot());
            left = left.subtract(take);
            log.info("E-invoice {}: {} of the shipping fee its rest invoice {} carries is taken off - payment waived "
                    + "it since", LogSafe.text(last.get().code()), take, LogSafe.text(rest.code()));
        }
        if (left.signum() > 0) {
            EInvoice standing = issuedRests.isEmpty() ? null : issuedRests.getFirst();
            log.warn("Order {}: {} of the shipping fee its rest invoices carry is waived and could not be taken off "
                    + "here", LogSafe.text(order.orderNumber()), left);
            if (standing != null) {
                events.publishEvent(new EInvoiceNeedsAttention(standing.id(), standing.code(), standing.orderId(),
                        order.orderNumber(), EInvoiceNeedsAttention.Cause.REDUCE_ON_PROVIDER_PORTAL, null,
                        standing.submittedAt(), standing.issueDueAt()));
            }
        }
        return last;
    }

    /**
     * The buyer copied again from the customer's legal profile, onto a draft (kltn-docs 21 §4.2 step 4). Still
     * incomplete, Sales are told what is missing again ({@link EInvoiceBuyerIncomplete}; told once per gap).
     */
    @Transactional
    @Auditable(action = AuditAction.UPDATE, resourceType = "einvoice", resourceId = "#id")
    public EInvoice.Snapshot refreshBuyer(UUID id, long expectedVersion) {
        EInvoice invoice = lock(id, expectedVersion);
        invoice.reviseBuyer(buyerOf(invoice.customerId()));
        EInvoice.Snapshot refreshed = invoices.save(invoice).snapshot();
        announceIncompleteBuyer(refreshed);
        return refreshed;
    }

    /**
     * Tells Sales, in this transaction, what the customer's profile lacks for the draft to be issued (HĐ-8; kltn-docs
     * 21 BR-06: "thiếu thì chặn, báo Sale bổ sung"): issuing is refused until it is there, and only Sales can record
     * it. Nothing when the buyer is complete.
     */
    private void announceIncompleteBuyer(EInvoice.Snapshot draft) {
        InvoiceParty buyer = draft.buyer();
        List<EInvoiceBuyerIncomplete.Missing> missing = new ArrayList<>();
        if (buyer.name() == null) {
            missing.add(EInvoiceBuyerIncomplete.Missing.NAME);
        }
        if (buyer.taxCode() == null) {
            missing.add(EInvoiceBuyerIncomplete.Missing.TAX_CODE);
        }
        if (buyer.address() == null) {
            missing.add(EInvoiceBuyerIncomplete.Missing.ADDRESS);
        }
        if (buyer.email() == null) {
            missing.add(EInvoiceBuyerIncomplete.Missing.EMAIL);
        }
        if (!missing.isEmpty() && draft.customerId() != null) {
            events.publishEvent(new EInvoiceBuyerIncomplete(draft.id(), draft.code(), draft.orderId(),
                    draft.orderNumber(), draft.customerId(), List.copyOf(missing)));
        }
    }

    /**
     * The accountant's corrections to a draft: the buyer as the invoice is to name it, and names of lines.
     *
     * @throws BusinessException {@code EINVOICE_NOT_DRAFT}; {@code VALIDATION_FAILED} for a tax code that is not 10,
     *         10-3 or 12 digits, an e-mail that is not an address, a blank name, or a line that is not the invoice's;
     *         {@code OPTIMISTIC_LOCK}
     */
    @Transactional
    @Auditable(action = AuditAction.UPDATE, resourceType = "einvoice", resourceId = "#command.id()")
    public EInvoice.Snapshot revise(Revise command) {
        EInvoice invoice = lock(command.id(), command.expectedVersion());
        InvoiceParty current = invoice.buyer();
        invoice.reviseBuyer(new InvoiceParty(
                command.buyerName() == null ? current.name() : limited(command.buyerName(), "buyerName", 200),
                command.buyerTaxCode() == null ? current.taxCode() : taxCode(command.buyerTaxCode()),
                command.buyerAddress() == null ? current.address() : limited(command.buyerAddress(), "buyerAddress", 500),
                command.buyerEmail() == null ? current.email() : email(command.buyerEmail())));
        invoice.renameLines(command.lineNames());
        return invoices.save(invoice).snapshot();
    }

    /** DRAFT → VOIDED_DRAFT with a reason: a draft that should not be issued. Its shipment may be drawn up again. */
    @Transactional
    @Auditable(action = AuditAction.TRANSITION, resourceType = "einvoice", resourceId = "#id")
    public EInvoice.Snapshot voidDraft(UUID id, long expectedVersion, String reason, UUID by) {
        EInvoice invoice = lock(id, expectedVersion);
        invoice.voidDraft(reason, by, clock.instant());
        return invoices.save(invoice).snapshot();
    }

    /**
     * The accountant voids the reduction of a failed delivery that no provider can send (WF-05 BE-10, decision Q-8;
     * F-14), to make it on the provider's portal and record it there ({@code EInvoiceIssuance.recordExternalReduction}).
     * Such a reduction used to be neither sendable nor voidable, and the order it belonged to waited on it for good.
     *
     * <p><b>Only when it really cannot be sent</b> - otherwise the reduction would leave the system for a portal by
     * choice, and the two would drift: the provider that issued the invoice it reduces is not usable here any more
     * (every correction goes through it, HĐ-5), or issues no corrections at all (Viettel's adapter), or it reduces a
     * replacement, which no provider here adjusts (Q-9: one drawn up before that rule), or the provider refused it
     * {@value #REFUSED_FOR_GOOD} times under the same code. A draft or a refused one; one at the provider is
     * waited for. The invoice it reduces stays reversed - the shipment, delivered again, still takes its own original -
     * and the accountants are told to reduce it on the portal ({@code REDUCE_ON_PROVIDER_PORTAL}), in this
     * transaction.</p>
     *
     * @throws BusinessException {@code EINVOICE_NOT_FOUND}; {@code OPTIMISTIC_LOCK}; {@code
     *         EINVOICE_CORRECTION_NOT_APPLICABLE} for anything but the reduction of a failed delivery; {@code
     *         EINVOICE_INVALID_TRANSITION} once it is at the provider or issued; {@code EINVOICE_REVERSAL_STILL_SENDABLE}
     *         while a provider can still send it
     */
    @Transactional
    @Auditable(action = AuditAction.TRANSITION, resourceType = "einvoice", resourceId = "#id",
            details = "{reasonCode: 'UNSENDABLE_REVERSAL', reason: #reason}")
    public EInvoice.Snapshot voidUnsendableReversal(UUID id, long expectedVersion, String reason, UUID by) {
        EInvoice.Snapshot unlocked = invoices.findById(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.EINVOICE_NOT_FOUND)).snapshot();
        invoices.lockOrder(unlocked.orderId());
        EInvoice reduction = lock(id, expectedVersion);
        if (reduction.correctionBasis() != com.stockflow.invoicing.internal.domain.CorrectionBasis.DELIVERY_FAILED) {
            throw new BusinessException(ErrorCode.EINVOICE_CORRECTION_NOT_APPLICABLE,
                    "Invoice %s does not reduce a failed delivery".formatted(reduction.code()));
        }
        if (reduction.status() != EInvoiceStatus.DRAFT && reduction.status() != EInvoiceStatus.REJECTED) {
            throw new BusinessException(ErrorCode.EINVOICE_INVALID_TRANSITION, ("Invoice %s is %s: only a draft or a "
                    + "refused reduction is voided").formatted(reduction.code(), reduction.status()));
        }
        EInvoice.Snapshot reduced = invoices.findById(reduction.originalEInvoiceId()).orElseThrow().snapshot();
        if (sendable(reduced, reduction.snapshot())) {
            throw new BusinessException(ErrorCode.EINVOICE_REVERSAL_STILL_SENDABLE, ("Invoice %s can still be sent "
                    + "through %s: issue it, or reopen and issue it again").formatted(reduction.code(),
                    reduced.provider()));
        }
        if (reduction.status() == EInvoiceStatus.REJECTED) {
            reduction.reopen();
            reduction = invoices.save(reduction);
        }
        reduction.voidDraft(reason, EInvoice.VoidReasonCode.UNSENDABLE_REVERSAL, by, clock.instant());
        EInvoice.Snapshot voided = invoices.save(reduction).snapshot();
        events.publishEvent(new EInvoiceNeedsAttention(reduced.id(), reduced.code(), reduced.orderId(),
                reduced.orderNumber(), EInvoiceNeedsAttention.Cause.REDUCE_ON_PROVIDER_PORTAL, null,
                reduced.submittedAt(), reduced.issueDueAt()));
        return voided;
    }

    /** How many refusals under one code make a reduction "refused for good" (BE-10). */
    static final int REFUSED_FOR_GOOD = 2;

    /**
     * Whether a reduction of {@code reduced} can still go through a provider: the one that issued it, usable here, issuing
     * corrections, and not having refused this reduction {@value #REFUSED_FOR_GOOD} times under the same code.
     */
    private boolean sendable(EInvoice.Snapshot reduced, EInvoice.Snapshot reduction) {
        return correctionSendable(providers, invoices, reduced, reduction);
    }

    /**
     * The one rule for whether a correction of {@code reduced} can go through a provider (Q-8, HĐ-5), shared by the void
     * of a failed delivery's unsendable reduction and the record of a return's reduction made on the portal (audit r3
     * EINV-2): the provider that issued {@code reduced}, usable here, issuing corrections, adjusting {@code reduced}'s
     * kind, and not having refused {@code correction} {@value #REFUSED_FOR_GOOD} times under one code.
     */
    static boolean correctionSendable(EInvoiceProviders providers, EInvoiceRepository invoices,
                                      EInvoice.Snapshot reduced, EInvoice.Snapshot reduction) {
        String provider = reduced.provider();
        if (provider == null || !providers.usable(provider)) {
            return false;
        }
        Optional<EInvoiceProvider> port = providers.find(provider);
        if (port.isEmpty() || !port.get().issuesCorrections()) {
            return false;
        }
        if (reduced.type() == EInvoiceType.REPLACEMENT && !port.get().adjustsReplacements()) {
            // Drawn up before a replacement stopped being adjusted (Q-9): no provider here takes it (SePay 422, T-5).
            return false;
        }
        return reduction.rejectionCode() == null
                || invoices.refusalsUnder(reduction.id(), reduction.rejectionCode()) < REFUSED_FOR_GOOD;
    }

    /** REJECTED → DRAFT, to correct what the provider refused and issue again. */
    @Transactional
    @Auditable(action = AuditAction.TRANSITION, resourceType = "einvoice", resourceId = "#id")
    public EInvoice.Snapshot reopen(UUID id, long expectedVersion) {
        EInvoice invoice = lock(id, expectedVersion);
        invoice.reopen();
        return invoices.save(invoice).snapshot();
    }

    /** What became of a shipment's invoice when its delivery was corrected to a failure. */
    public enum CorrectionOutcome {
        /** The shipment had no invoice in force. */
        NO_INVOICE,
        /** Its draft - never sent, or sent and refused - was voided. */
        VOIDED,
        /**
         * Its invoice is still at the provider, which may yet issue it or refuse it: nothing can be done to it now. It
         * is marked ({@link EInvoiceRepository#markDeliveryFailed}) and settled when the provider answers
         * ({@link #settleCorrectedDelivery}): reduced in full once issued, voided once refused.
         */
        AWAITING_PROVIDER,
        /**
         * Its invoice is issued: it cannot be voided. The issued original, or the replacement standing for the
         * shipment, is reduced in full by the system ({@link #reverseForCorrectedDelivery}); one that something already
         * corrects is the accountant's to adjust (E4).
         */
        LEFT_FOR_ADJUSTMENT
    }

    /**
     * The delivery of a shipment was corrected to a failure (kltn-docs 09 §4.3 step 5; 21 §4.5): nothing was delivered,
     * so an invoice that was never issued for it is voided by the system, with the reason. A rejected one goes back to
     * a draft first - no invoice exists at the provider for it - and is voided the same way.
     *
     * <p>An invoice still at the provider is marked, to be settled when the provider answers (WF-05 HĐ-3): it may exist
     * in law by then, or never. An issued one is not touched here: it is only ever adjusted (21 §4.4: a full decrease,
     * {@link #reverseForCorrectedDelivery}).</p>
     *
     * <p><b>A replacement on its way</b> (review finding 4): the issued original still stands for the shipment while its
     * replacement is drawn up, refused or at the provider, and the original can be neither reduced nor replaced again
     * while that replacement lives. Drawn up or refused, the replacement is voided - nothing was delivered for it to
     * correct - and the original is then reduced in full like any issued invoice. At the provider, the replacement is
     * the one marked: once it is issued it stands for the shipment and is reduced; refused, it is voided and the
     * original reduced ({@link #settleCorrectedDelivery}). The replacement is locked before the original, the order
     * the provider's answer locks them in ({@code EInvoiceIssuanceSteps.issued} replaces the original).</p>
     */
    @Transactional
    public CorrectionOutcome voidForCorrectedDelivery(UUID orderId, UUID shipmentId) {
        // The lock a draft is drawn up under: a draft being drawn up for this shipment right now is finished first,
        // and found here.
        invoices.lockOrder(orderId);
        if (deliveredNow(orderId, shipmentId)) {
            return CorrectionOutcome.NO_INVOICE;
        }
        Optional<EInvoice> inForce = invoices.findLiveOriginalOfShipment(shipmentId);
        if (inForce.isEmpty()) {
            return CorrectionOutcome.NO_INVOICE;
        }
        if (inForce.get().status() == EInvoiceStatus.ISSUED) {
            Optional<EInvoice> pending = invoices.findPendingReplacementOf(inForce.get().id());
            if (pending.isPresent()) {
                EInvoice replacement = invoices.findByIdForUpdate(pending.get().id()).orElseThrow();
                switch (replacement.status()) {
                    case SUBMITTING -> {
                        invoices.markDeliveryFailed(replacement.id(), clock.instant());
                        return CorrectionOutcome.AWAITING_PROVIDER;
                    }
                    case DRAFT, REJECTED -> voidUnissued(replacement);
                    // Issued since it was read: it stands for the shipment now, and is what is reduced.
                    default -> { }
                }
            }
        }
        EInvoice invoice = invoices.findByIdForUpdate(inForce.get().id()).orElseThrow();
        return switch (invoice.status()) {
            case SUBMITTING -> {
                invoices.markDeliveryFailed(invoice.id(), clock.instant());
                yield CorrectionOutcome.AWAITING_PROVIDER;
            }
            case DRAFT, REJECTED -> {
                voidUnissued(invoice);
                yield CorrectionOutcome.VOIDED;
            }
            default -> CorrectionOutcome.LEFT_FOR_ADJUSTMENT;
        };
    }

    /**
     * The delivery of a shipment whose invoice is issued was corrected to a failure (kltn-docs 21 §4.4 and the edge case
     * "hoá đơn đã Issued" of §6; 09 §4.3 step 5; WF-05 HĐ-3): the system draws up the full reduction of the invoice
     * standing for the shipment - its original, or the replacement that took the original's place - as a draft
     * ({@code CorrectionBasis.DELIVERY_FAILED}, {@link EInvoice#drawUpDeliveryReversal}) for the accountant to issue,
     * and from now on that invoice no longer stands for the shipment ({@link EInvoiceRepository#markReversed}).
     * Delivered again, the shipment is drawn up anew by {@link #drawUp}, as 21 §4.5 step 14 and BR-01 ask: one original
     * in force per delivered shipment, dated the day it really was delivered.
     *
     * <p>Empty - nothing drawn up, the accountant told instead - when the invoice in force is not issued (still at the
     * provider: {@link #settleCorrectedDelivery} does it once issued), or something already corrects it - an
     * adjustment, issued or not, or a replacement being drawn up - so that taking every line off would reduce it below
     * nothing or reduce the wrong invoice. Those are the accountant's to bring to nothing by hand. A second call finds
     * the invoice reversed already, and nothing in force: empty as well.</p>
     *
     * <p>Under the order's invoicing lock, in the caller's transaction: the listener's, which voided a draft or found
     * this invoice under the same lock a moment before. A shipment delivered again before this was heard (its event
     * retried late) kept the old invoice when it was drawn up, and is drawn up by hand afterwards.</p>
     */
    @Transactional
    public Optional<EInvoice.Snapshot> reverseForCorrectedDelivery(UUID orderId, UUID shipmentId) {
        return Optional.ofNullable(reverseOrExplain(orderId, shipmentId).drawn());
    }

    /**
     * {@link #reverseForCorrectedDelivery}, saying why nothing was drawn up: the listener tells the accountants the
     * cause ({@code REPLACEMENT_NOT_REDUCIBLE}, {@code DELIVERY_FAILED_NOT_REVERSED}) instead of a notice that names
     * none (BE-09, BE-11).
     */
    @Transactional
    public Reversal reverseOrExplain(UUID orderId, UUID shipmentId) {
        invoices.lockOrder(orderId);
        if (deliveredNow(orderId, shipmentId)) {
            return Reversal.NOTHING;
        }
        Optional<EInvoice> inForce = invoices.findLiveOriginalOfShipment(shipmentId);
        if (inForce.isEmpty()) {
            return Reversal.NOTHING;
        }
        return reverse(invoices.findByIdForUpdate(inForce.get().id()).orElseThrow());
    }

    /**
     * The accountant's "reduce for the failed delivery" (WF-05 BE-11, F-15): the system's reduction run again for an
     * issued invoice of a shipment that is not delivered now - once a correction that was at the provider is settled,
     * or when the system's own attempt could not reduce it. The same rules as the system's ({@link #reverse}).
     *
     * @throws BusinessException {@code EINVOICE_NOT_FOUND}; {@code OPTIMISTIC_LOCK}; {@code
     *         EINVOICE_REVERSAL_NOT_APPLICABLE} for an invoice that is not issued, not an original of a shipment, no
     *         longer stands for it, is reversed already, whose shipment is delivered now, or that cannot be reduced
     *         yet (a correction at the provider) or by the system at all; {@code EINVOICE_REPLACEMENT_NOT_ADJUSTABLE}
     *         for a replacement (Q-9)
     */
    @Transactional
    @Auditable(action = AuditAction.CREATE, resourceType = "einvoice", resourceId = "#id")
    public EInvoice.Snapshot reduceForFailedDelivery(UUID id, long expectedVersion) {
        EInvoice.Snapshot unlocked = invoices.findById(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.EINVOICE_NOT_FOUND)).snapshot();
        invoices.lockOrder(unlocked.orderId());
        EInvoice invoice = lock(id, expectedVersion);
        EInvoice.Snapshot standing = invoice.snapshot();
        if (standing.status() != EInvoiceStatus.ISSUED || standing.type() == EInvoiceType.ADJUSTMENT
                || standing.shipmentId() == null || standing.reversedAt() != null) {
            throw new BusinessException(ErrorCode.EINVOICE_REVERSAL_NOT_APPLICABLE, ("Invoice %s is not an issued "
                    + "invoice of a shipment still in force").formatted(standing.code()));
        }
        if (shipments.find(standing.orderId(), standing.shipmentId()).isPresent()) {
            throw new BusinessException(ErrorCode.EINVOICE_REVERSAL_NOT_APPLICABLE,
                    "The shipment of invoice %s is delivered: nothing failed".formatted(standing.code()));
        }
        Optional<EInvoice> inForce = invoices.findLiveOriginalOfShipment(standing.shipmentId());
        if (inForce.isEmpty() || !inForce.get().id().equals(id)) {
            throw new BusinessException(ErrorCode.EINVOICE_REVERSAL_NOT_APPLICABLE,
                    "Invoice %s no longer stands for its shipment".formatted(standing.code()));
        }
        Reversal reversal = reverse(invoice);
        if (reversal.drawn() != null) {
            EInvoice.Snapshot drawn = reversal.drawn();
            events.publishEvent(new EInvoiceNeedsAttention(drawn.id(), drawn.code(), drawn.orderId(),
                    drawn.orderNumber(), EInvoiceNeedsAttention.Cause.DELIVERY_FAILED_REVERSAL_DRAWN_UP, null, null,
                    drawn.issueDueAt()));
            return drawn;
        }
        if (reversal.notReversed() == EInvoiceNeedsAttention.Cause.REPLACEMENT_NOT_REDUCIBLE) {
            throw new BusinessException(ErrorCode.EINVOICE_REPLACEMENT_NOT_ADJUSTABLE, ("Invoice %s is a replacement: "
                    + "its failed delivery is reduced on the provider's portal and recorded").formatted(standing.code()));
        }
        throw new BusinessException(ErrorCode.EINVOICE_REVERSAL_NOT_APPLICABLE, ("Invoice %s cannot be reduced now: a "
                + "correction of it is at the provider, or an adjustment took a line below what it invoiced")
                .formatted(standing.code()));
    }

    /**
     * Whether the shipment is delivered as it stands now, for a failure being acted on (second review, finding 1). The
     * event saying its delivery was corrected to a failure is handled after commit and may be handled late - retried,
     * or replayed at a restart - after the failure was itself corrected back to delivered. Acting on it then would void
     * the draft, or reduce the invoice, of a delivered shipment, and nothing would draw it up again: the shipment as it
     * is now decides, not the step the event describes.
     */
    private boolean deliveredNow(UUID orderId, UUID shipmentId) {
        if (shipmentId == null || shipments.find(orderId, shipmentId).isEmpty()) {
            return false;
        }
        log.info("Shipment {} of order {} is delivered now: a correction to a failure heard late changes nothing",
                shipmentId, orderId);
        return true;
    }

    /** What {@link #settleCorrectedDelivery} did with an invoice whose delivery failed while it was at the provider. */
    public enum Settlement {
        /** Still at the provider - sent again under the same transaction, unanswered - or not marked: nothing yet. */
        WAITING,
        /** Issued, and reduced in full now. */
        REVERSED,
        /** Issued, but something corrects it already: the accountant reduces it by hand, and is told. */
        LEFT_FOR_ADJUSTMENT,
        /** Refused, or taken back from the provider: voided, never sent again. */
        VOIDED,
        /** Reduced or voided before - this is the same answer heard again. */
        DONE_BEFORE,
        /**
         * The shipment was delivered after all - its delivery corrected back to delivered, or delivered on a retry -
         * before this answer was settled: an issued invoice stands as it is; a refused one loses its mark and is
         * corrected and sent again as any refused invoice, the accountants told of the refusal.
         */
        DELIVERED_AFTER_ALL
    }

    /**
     * What was decided for an invoice of a failed delivery once the provider answered.
     *
     * @param deliveredAgain whether the shipment is delivered now - delivered again while the provider had the old
     *                       invoice. The new delivery found the old invoice still standing and drew nothing up, so the
     *                       caller draws it up once this transaction has let the order's lock go
     */
    public record Settled(Settlement settlement, UUID orderId, UUID shipmentId, boolean deliveredAgain) {

        /** Whether the invoice no longer stands for its shipment, so that a delivery of it again is drawn up anew. */
        public boolean released() {
            return settlement == Settlement.REVERSED || settlement == Settlement.VOIDED
                    || settlement == Settlement.DONE_BEFORE;
        }
    }

    /**
     * Settles an invoice whose delivery was corrected to a failure while it was at the provider
     * ({@link EInvoiceRepository#markDeliveryFailed}; WF-05 HĐ-3), now that the provider answered:
     * <ul>
     *   <li><b>issued</b> - it exists in law and invoices goods nobody received: reduced in full exactly as an invoice
     *       issued before the correction is ({@link #reverseForCorrectedDelivery}), and the accountants are told to
     *       issue the reduction. No agreement of the buyer is needed: the invoice was right when drawn up, the
     *       delivery was what changed;</li>
     *   <li><b>refused</b> (or taken back by the accountant) - no invoice exists: voided, so it is never corrected and
     *       sent again for goods that were not delivered. The accountants are not told of the refusal itself
     *       ({@code EInvoiceIssuanceSteps.rejected}): there is nothing for them to put right.</li>
     * </ul>
     *
     * <p>In a transaction of its own ({@code REQUIRES_NEW}) and under the order's invoicing lock, for the reason
     * {@link #drawUp} gives: the caller draws the redelivery up afterwards, under that same lock, in a transaction of
     * its own, which could not take the lock while this one held it. Heard again, it finds the invoice reversed or
     * voided already and changes nothing.</p>
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Settled settleCorrectedDelivery(UUID invoiceId) {
        EInvoice.Snapshot unlocked = invoices.findById(invoiceId)
                .orElseThrow(() -> new BusinessException(ErrorCode.EINVOICE_NOT_FOUND)).snapshot();
        invoices.lockOrder(unlocked.orderId());
        EInvoice invoice = invoices.findByIdForUpdate(invoiceId).orElseThrow();
        Optional<DeliveredShipment> delivered = unlocked.shipmentId() == null ? Optional.empty()
                : shipments.find(unlocked.orderId(), unlocked.shipmentId());
        // Audit r2 M-3: delivered again on another day is a new delivery, not the old one after all - the invoice of
        // the failed day is settled as if it had stayed failed, and the caller draws the new day's original up.
        Settlement settlement = delivered.isPresent() && !redeliveredOnAnotherDay(invoice.snapshot(), delivered.get())
                ? deliveredAfterAll(invoice) : settlementOf(invoice);
        return new Settled(settlement, unlocked.orderId(), unlocked.shipmentId(), delivered.isPresent());
    }

    /**
     * Review finding 5: the shipment of a marked invoice is delivered now, on the day the invoice stands for - the
     * failure was itself corrected, or the carrier delivered it on a retry that day - before the provider's answer was
     * settled (on another day it is a new delivery, audit r2 M-3: settled as a failure). Nothing failed after all: an issued
     * invoice stands (reducing it and drawing the same delivery up again would give the customer three invoices for
     * one delivery), and a refused one goes the way of any refusal - its mark taken back, so it can be corrected and
     * sent again, and the accountants told of the refusal its mark kept quiet ({@code EInvoiceIssuanceSteps.rejected}).
     * One already voided or reduced was settled before the delivery was restored: the new delivery draws up its own.
     */
    private Settlement deliveredAfterAll(EInvoice invoice) {
        if (!invoices.deliveryFailed(invoice.id())) {
            return Settlement.WAITING;
        }
        switch (invoice.status()) {
            case SUBMITTING:
                return Settlement.WAITING;
            case VOIDED_DRAFT:
                return Settlement.DONE_BEFORE;
            case DRAFT:
            case REJECTED:
                invoices.clearDeliveryFailed(invoice.id());
                announceRefusal(invoice.snapshot());
                return Settlement.DELIVERED_AFTER_ALL;
            default:
                break;
        }
        Optional<EInvoice> inForce = invoices.findLiveOriginalOfShipment(invoice.snapshot().shipmentId());
        return inForce.isPresent() && inForce.get().id().equals(invoice.id())
                ? Settlement.DELIVERED_AFTER_ALL : Settlement.DONE_BEFORE;
    }

    /** The notice {@code EInvoiceIssuanceSteps.rejected} gives a refusal, for one whose mark kept it quiet. */
    void announceRefusal(EInvoice.Snapshot refused) {
        if (refused.status() != EInvoiceStatus.REJECTED) {
            return;
        }
        events.publishEvent(new EInvoiceNeedsAttention(refused.id(), refused.code(), refused.orderId(),
                refused.orderNumber(), EInvoiceNeedsAttention.Cause.REJECTED, refused.rejectionCode(),
                refused.submittedAt(), refused.issueDueAt()));
    }

    /**
     * The delivery of a shipment whose invoice was marked at the provider ({@link #voidForCorrectedDelivery}) was
     * corrected back to delivered, or delivered on the carrier's retry, before the provider answered (review finding
     * 5): the mark is taken back, so the provider's answer is taken as for any invoice - issued, it stands; refused, it
     * is corrected and sent again. A marked invoice the provider refused in the meantime is told as a refusal; one
     * already settled is left to {@link #settleCorrectedDelivery}, which reads the shipment as it is now.
     *
     * @return how many invoices lost their mark
     */
    @Transactional
    public int restoreDelivered(UUID orderId, UUID shipmentId) {
        invoices.lockOrder(orderId);
        Optional<DeliveredShipment> delivered = shipmentId == null ? Optional.empty()
                : shipments.find(orderId, shipmentId);
        int restored = 0;
        for (EInvoice marked : invoices.findUnsettledDeliveryFailedOfShipment(shipmentId)) {
            EInvoice invoice = invoices.findByIdForUpdate(marked.id()).orElseThrow();
            if (delivered.isPresent() && redeliveredOnAnotherDay(invoice.snapshot(), delivered.get())) {
                // Audit r2 M-3: the mark stays - once the provider answers, the invoice of the failed day is reduced
                // or voided and the new day's original drawn up ({@link #settleCorrectedDelivery}).
                continue;
            }
            if (invoices.clearDeliveryFailed(invoice.id())) {
                announceRefusal(invoice.snapshot());
                restored++;
            }
        }
        if (delivered.isPresent() && takeBackReversal(shipmentId, delivered.get())) {
            restored++;
        }
        return restored;
    }

    /**
     * Audit r2 M-3 (decision §3 HĐ-3 "giao lại thành công thì lập hoá đơn gốc mới"; NĐ 254/2026 Điều 9 khoản 1: an
     * invoice is dated the day ownership passed): whether the shipment, delivered now, was delivered on another day than
     * the one {@code invoice} stands for. A delivery corrected back to delivered on the same day is the same delivery,
     * and its invoice stands for it again; one delivered on a later day is a new delivery, which takes an original of
     * its own dated that day - the old invoice, dated the failed day, would say the goods changed hands when they did
     * not, and the payment and return windows, which count from the new day, would disagree with it. The one rule for
     * every path that may take a failure back: the reduction of an issued invoice ({@link #takeBackReversal}), the mark
     * of one at the provider ({@link #restoreDelivered}) and the provider's answer for it
     * ({@link #settleCorrectedDelivery}).
     *
     * <p>False when the day cannot be told - no delivery time, no delivery day on the invoice or its original - so such
     * an invoice is treated as before: taken back.</p>
     */
    private boolean redeliveredOnAnotherDay(EInvoice.Snapshot invoice, DeliveredShipment delivered) {
        LocalDate day = deliveryDayOf(invoice);
        return day != null && delivered.deliveredAt() != null
                && !day.equals(BusinessCalendar.date(delivered.deliveredAt()));
    }

    /**
     * The day of the delivery an invoice stands for: an original's {@code deliveredOn}, or - drawn up before that
     * column (V20261219000400) - its own date, which was its delivery's then; a replacement's is its original's.
     */
    private LocalDate deliveryDayOf(EInvoice.Snapshot invoice) {
        if (invoice.deliveredOn() != null) {
            return invoice.deliveredOn();
        }
        if (invoice.type() == EInvoiceType.ORIGINAL) {
            return invoice.issueDate();
        }
        return invoice.type() == EInvoiceType.REPLACEMENT && invoice.originalEInvoiceId() != null
                ? invoices.findById(invoice.originalEInvoiceId()).map(original -> deliveryDayOf(original.snapshot()))
                .orElse(null)
                : null;
    }

    /**
     * WF-05 BE-29: the delivery of a shipment whose issued invoice had been reduced for its failure is corrected back to
     * delivered, on the day that invoice stands for, while that reduction has not left - a draft, or refused. Delivered
     * on another day it is a new delivery (audit r2 M-3, {@link #redeliveredOnAnotherDay}): nothing is taken back, the
     * reduction is issued and the new day's original stands. On the same day nothing failed after all: the reduction is
     * voided ({@code DELIVERY_CORRECTED_BACK}) and the invoice stands for the shipment again ({@code reversed_at} taken
     * back), in this one transaction - the only way {@code tg_einvoice_guard} lets the mark go. Without it the shipment
     * got the old invoice, its reduction (which could not be voided) and a new original: three invoices for one
     * delivery (V1).
     *
     * <p>A new original already drawn up for the redelivery is a second invoice of the same delivery: voided too if it
     * never left. Once it, or the reduction, is at the provider or issued, nothing is taken back - the reduction is
     * issued and the new original stands, which is lawful, only longer. Called under the order's lock, and only while
     * the shipment is delivered now (an event heard late changes nothing).</p>
     *
     * @return whether the reversal was taken back
     */
    private boolean takeBackReversal(UUID shipmentId, DeliveredShipment delivered) {
        Optional<EInvoice> reversed = invoices.findReversedOfShipment(shipmentId);
        if (reversed.isEmpty()) {
            return false;
        }
        if (redeliveredOnAnotherDay(reversed.get().snapshot(), delivered)) {
            // Audit r2 M-3: a new delivery - the reduction stands and the new day's original is the delivery's own.
            log.info("E-invoice {} stays reduced: its shipment was delivered again on another day, which takes an "
                    + "original of its own", LogSafe.text(reversed.get().code()));
            return false;
        }
        Optional<EInvoice> reduction = invoices.findLiveDeliveryFailedReductionOf(reversed.get().id());
        if (reduction.isEmpty() || (reduction.get().status() != EInvoiceStatus.DRAFT
                && reduction.get().status() != EInvoiceStatus.REJECTED)) {
            return false;
        }
        Optional<EInvoice> redelivery = invoices.findLiveOriginalOfShipment(shipmentId);
        if (redelivery.isPresent() && redelivery.get().status() != EInvoiceStatus.DRAFT
                && redelivery.get().status() != EInvoiceStatus.REJECTED) {
            return false;
        }
        EInvoice original = invoices.findByIdForUpdate(reversed.get().id()).orElseThrow();
        if (redelivery.isPresent()) {
            EInvoice duplicate = invoices.findByIdForUpdate(redelivery.get().id()).orElseThrow();
            voidUnissued(duplicate, "The delivery was corrected back to delivered: invoice %s stands for it again"
                    .formatted(original.code()));
        }
        EInvoice draft = invoices.findByIdForUpdate(reduction.get().id()).orElseThrow();
        if (draft.status() == EInvoiceStatus.REJECTED) {
            draft.reopen();
            draft = invoices.save(draft);
        }
        draft.voidDraft("The delivery was corrected back to delivered before this reduction was issued",
                EInvoice.VoidReasonCode.DELIVERY_CORRECTED_BACK, null, clock.instant());
        invoices.save(draft);
        invoices.clearReversed(original.id());
        log.info("E-invoice {} stands for its shipment again: its delivery was corrected back to delivered and its "
                + "reduction {} voided", LogSafe.text(original.code()), LogSafe.text(draft.code()));
        return true;
    }

    private Settlement settlementOf(EInvoice invoice) {
        if (!invoices.deliveryFailed(invoice.id())) {
            return Settlement.WAITING;
        }
        switch (invoice.status()) {
            case SUBMITTING:
                return Settlement.WAITING;
            case VOIDED_DRAFT:
                return Settlement.DONE_BEFORE;
            case DRAFT:
            case REJECTED:
                voidUnissued(invoice);
                if (invoice.type() == EInvoiceType.REPLACEMENT) {
                    // Review finding 4: the replacement will never take the original's place, and the original
                    // still invoices goods nobody received - reduced in full, if it is still what stands for the
                    // shipment (the shipment's invoices as they are now, not as they were when it was marked).
                    Settlement original = reduceIfStanding(invoice.snapshot().shipmentId(),
                            invoice.snapshot().originalEInvoiceId());
                    return original == Settlement.DONE_BEFORE ? Settlement.VOIDED : original;
                }
                return Settlement.VOIDED;
            default:
                break;
        }
        return reduceIfStanding(invoice.snapshot().shipmentId(), invoice.id());
    }

    /**
     * Reduces in full the issued invoice {@code expected} if it is what stands for the shipment now, telling the
     * accountants; DONE_BEFORE when it no longer stands - reversed already, or replaced since.
     */
    private Settlement reduceIfStanding(UUID shipmentId, UUID expected) {
        Optional<EInvoice> inForce = invoices.findLiveOriginalOfShipment(shipmentId);
        if (expected == null || inForce.isEmpty() || !inForce.get().id().equals(expected)
                || inForce.get().status() != EInvoiceStatus.ISSUED) {
            return Settlement.DONE_BEFORE;
        }
        EInvoice invoice = invoices.findByIdForUpdate(expected).orElseThrow();
        Reversal reversal = reverse(invoice);
        EInvoice.Snapshot issued = invoice.snapshot();
        if (reversal.drawn() != null) {
            EInvoice.Snapshot drawn = reversal.drawn();
            events.publishEvent(new EInvoiceNeedsAttention(drawn.id(), drawn.code(), drawn.orderId(),
                    drawn.orderNumber(), EInvoiceNeedsAttention.Cause.DELIVERY_FAILED_REVERSAL_DRAWN_UP, null, null,
                    drawn.issueDueAt()));
            return Settlement.REVERSED;
        }
        if (reversal.notReversed() == null) {
            return Settlement.DONE_BEFORE;
        }
        events.publishEvent(new EInvoiceNeedsAttention(issued.id(), issued.code(), issued.orderId(),
                issued.orderNumber(), reversal.notReversed(), null, issued.submittedAt(), issued.issueDueAt()));
        return Settlement.LEFT_FOR_ADJUSTMENT;
    }

    /**
     * Whether a draft about to be issued automatically still is what drawing it up now would give (WF-05 review,
     * defect 4). The automatic issuing takes drafts with no warning that {@linkplain EInvoice.Warning#blocksAutomaticIssue
     * holds it back}, and warnings are decided when a draft is drawn up: a draft drawn up before a rule existed - the VAT
     * reduction period (V20261201000200), a product's tax class (Q-21) - would be issued as if it never did. So, just
     * before it is sent, the same checks as a manual issue ({@link #recheckForIssue}):
     * <ul>
     *   <li>the buyer's gaps and a line at 0% are worked out again on the draft as it stands;</li>
     *   <li>each line's rate is compared with the one a draft drawn up now for the same delivery would carry, and with
     *       the rate its order fixed ({@link EInvoice.Warning#TAX_RATE_OUT_OF_DATE},
     *       {@link EInvoice.Warning#TAX_RATE_DIFFERS_FROM_CONTRACT}, {@link EInvoice.Warning#TAX_RATE_NOT_ON_CONTRACT}),
     *       and whether its product has a tax class now ({@link EInvoice.Warning#TAX_CLASS_UNKNOWN}). The lines carry no
     *       tax class; the delivery's facts do, and are what drawing it up reads.</li>
     * </ul>
     * Anything found is stored on the draft. A missing e-mail is stored and holds nothing back (Q-22, BR-06: the e-mail
     * is not invoice content, and the original must leave on its delivery day, Q-1). A shipment no longer delivered -
     * its delivery corrected to a failure - is not issued either, and is left to the correction, which voids its draft.
     *
     * @return true when it may be sent now; false when it may not, or is no longer a draft the automatic issuing takes
     */
    @Transactional
    public boolean recheckForAutomaticIssue(UUID id) {
        EInvoice invoice = invoices.findByIdForUpdate(id).orElse(null);
        if (invoice == null || invoice.status() != EInvoiceStatus.DRAFT || invoice.type() != EInvoiceType.ORIGINAL
                || blocking(invoice.warnings())) {
            return false;
        }
        Recheck recheck = recheck(invoice);
        if (!recheck.invoiceable()) {
            return false;
        }
        if (!recheck.found().isEmpty()) {
            invoices.save(invoice);
        }
        return !blocking(recheck.found());
    }

    /**
     * What a manual issue found (WF-05 BE-25, F-16): what the accountant must acknowledge before the draft is sent -
     * a warning that holds the automatic issuing back and was not on the draft the accountant read, or
     * {@link #BUYER_PROFILE_CHANGED} - and the version the draft is at now (one more when anything was stored).
     */
    public record IssueCheck(java.util.Set<String> unacknowledged, long version) {
    }

    /**
     * Not a stored warning: the customer's legal profile names the buyer otherwise than the draft does (BE-25). The
     * draft is not refreshed behind the accountant's back - a buyer they corrected by hand would be overwritten - so
     * they refresh it ({@code /buyer-refresh}) or acknowledge it.
     */
    public static final String BUYER_PROFILE_CHANGED = "BUYER_PROFILE_CHANGED";

    /**
     * The checks of the automatic issuing, before the accountant's issue (WF-05 BE-25, invariant V8: a manual issue
     * applies every check the automatic one does). It used to apply none: an old draft went out at a rate a reduction
     * period had changed, for a product that had lost its class, for a shipment whose delivery was corrected to a
     * failure meanwhile, or to a buyer the customer's profile no longer names.
     * <ul>
     *   <li>An original's shipment must be delivered now ({@code EINVOICE_NOT_INVOICEABLE}); a draft of a delivery
     *       corrected to a failure is voided by the correction, not issued.</li>
     *   <li>Its rates and tax classes are compared again and whatever is new is stored on the draft - in this
     *       transaction of its own, committed whatever the accountant does next.</li>
     *   <li>A new warning that holds the automatic issue back, or a buyer the profile names otherwise, must be in
     *       {@code acknowledged}; the caller refuses otherwise ({@code EINVOICE_DRAFT_OUT_OF_DATE}, listing them).</li>
     * </ul>
     * A correction is not checked here: its lines are the original's, at the original's rates (NĐ 174 keeps the rate
     * of the invoice corrected). A draft that is not one any more is left to the submission, which refuses it.
     *
     * @param expectedVersion the version the accountant read; null when the system issues
     * @throws BusinessException {@code EINVOICE_NOT_FOUND}; {@code OPTIMISTIC_LOCK}; {@code EINVOICE_NOT_INVOICEABLE}
     */
    @Transactional
    public IssueCheck recheckForIssue(UUID id, Long expectedVersion, java.util.Set<String> acknowledged) {
        EInvoice invoice = invoices.findByIdForUpdate(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.EINVOICE_NOT_FOUND));
        if (expectedVersion != null && invoice.version() != expectedVersion) {
            throw new BusinessException(ErrorCode.OPTIMISTIC_LOCK,
                    "Invoice %s changed since it was read".formatted(invoice.code()));
        }
        if (invoice.status() != EInvoiceStatus.DRAFT || invoice.type() != EInvoiceType.ORIGINAL) {
            return new IssueCheck(java.util.Set.of(), invoice.version());
        }
        Recheck recheck = recheck(invoice);
        if (!recheck.invoiceable()) {
            throw new BusinessException(ErrorCode.EINVOICE_NOT_INVOICEABLE, ("The shipment of invoice %s is not "
                    + "delivered now: its draft is voided by the correction, not issued").formatted(invoice.code()));
        }
        java.util.Set<String> unacknowledged = new java.util.TreeSet<>();
        recheck.found().stream().filter(EInvoice.Warning::blocksAutomaticIssue).map(Enum::name)
                .forEach(unacknowledged::add);
        InvoiceParty profile = buyerOf(invoice.customerId());
        if (!sameBuyer(profile, invoice.buyer())) {
            unacknowledged.add(BUYER_PROFILE_CHANGED);
        }
        if (acknowledged != null) {
            unacknowledged.removeAll(acknowledged);
        }
        long version = invoice.version();
        if (!recheck.found().isEmpty()) {
            version = invoices.save(invoice).version();
        }
        return new IssueCheck(java.util.Set.copyOf(unacknowledged), version);
    }

    private static boolean sameBuyer(InvoiceParty a, InvoiceParty b) {
        return java.util.Objects.equals(a.name(), b.name()) && java.util.Objects.equals(a.taxCode(), b.taxCode())
                && java.util.Objects.equals(a.address(), b.address());
    }

    private static boolean blocking(java.util.Set<EInvoice.Warning> warnings) {
        return warnings.stream().anyMatch(EInvoice.Warning::blocksAutomaticIssue);
    }

    /** What re-checking a draft found: whether its shipment is still delivered, and the warnings added now. */
    private record Recheck(boolean invoiceable, java.util.Set<EInvoice.Warning> found) {
    }

    /** The rate and class checks of {@link #recheckForAutomaticIssue}, shared with the manual issue. */
    private Recheck recheck(EInvoice invoice) {
        EInvoice.Snapshot draft = invoice.snapshot();
        RateCheck rates;
        if (draft.shipmentId() != null) {
            Optional<DeliveredShipment> delivered = shipments.find(draft.orderId(), draft.shipmentId());
            if (delivered.isEmpty()) {
                return new Recheck(false, java.util.Set.of());
            }
            rates = rates(draft.lines(), delivered.get());
        } else {
            Optional<OrderShippingFee> fees = shipments.orderFees(draft.orderId(),
                    draft.issueDate().atStartOfDay(BusinessCalendar.ZONE).toInstant());
            rates = fees.isEmpty() ? new RateCheck(false, false, false, false) : feeRest(draft.lines(), fees.get());
        }
        return new Recheck(true, invoice.recheckBeforeIssue(rates.outOfDate(), rates.differsFromContract(),
                rates.notOnContract(), rates.taxClassUnknown()));
    }

    private record RateCheck(boolean outOfDate, boolean differsFromContract, boolean notOnContract,
                             boolean taxClassUnknown) {
    }

    /** The draft's lines against the delivery's: goods by order line, fees by kind and SKU. */
    private static RateCheck rates(List<EInvoiceLine> lines, DeliveredShipment delivered) {
        boolean outOfDate = false;
        boolean differs = false;
        boolean notOnContract = false;
        boolean classUnknown = false;
        for (EInvoiceLine line : lines) {
            BigDecimal now = null;
            BigDecimal contract = null;
            boolean unknown = false;
            boolean found = false;
            if (line.kind() == EInvoiceLine.Kind.GOODS) {
                for (DeliveredShipment.Line goods : delivered.lines()) {
                    if (goods.orderLineId().equals(line.orderLineId())) {
                        now = goods.taxRate();
                        contract = goods.contractTaxRate();
                        unknown = goods.taxClassUnknown();
                        found = true;
                    }
                }
            } else if (line.kind() == EInvoiceLine.Kind.FEE) {
                for (DeliveredShipment.Fee fee : delivered.fees()) {
                    if (fee.kind().name().equals(line.feeKind().name())
                            && java.util.Objects.equals(fee.sku(), line.sku())) {
                        now = fee.taxRate();
                        contract = fee.contractTaxRate();
                        unknown = fee.taxClassUnknown();
                        found = true;
                    }
                }
            }
            if (!found) {
                continue;
            }
            outOfDate |= now.compareTo(line.taxRate()) != 0;
            differs |= differs(line.taxRate(), contract);
            notOnContract |= contract == null;
            classUnknown |= unknown;
        }
        return new RateCheck(outOfDate, differs, notOnContract, classUnknown);
    }

    /**
     * The rest of an order's fees - the shipping fee no delivery carried and the plate fees none did (BE-62) - against
     * the fees as the order has them on the invoice's day.
     */
    private static RateCheck feeRest(List<EInvoiceLine> lines, OrderShippingFee fees) {
        boolean outOfDate = false;
        boolean differs = false;
        boolean notOnContract = false;
        boolean classUnknown = false;
        for (EInvoiceLine line : lines) {
            if (line.kind() != EInvoiceLine.Kind.FEE) {
                continue;
            }
            DeliveredShipment.Fee fee = line.feeKind() == EInvoiceLine.FeeKind.SHIPPING_FEE ? fees.fee()
                    : fees.plateFees().stream().filter(plate -> java.util.Objects.equals(plate.sku(), line.sku()))
                    .findFirst().orElse(null);
            if (fee == null) {
                continue;
            }
            outOfDate |= fee.taxRate().compareTo(line.taxRate()) != 0;
            differs |= differs(line.taxRate(), fee.contractTaxRate());
            notOnContract |= fee.contractTaxRate() == null;
            classUnknown |= fee.taxClassUnknown();
        }
        return new RateCheck(outOfDate, differs, notOnContract, classUnknown);
    }

    /**
     * What {@link #reverse} did: the reduction drawn up, or - when none could be - why, as the notice the accountants
     * are given ({@code REPLACEMENT_NOT_REDUCIBLE}: a replacement is never adjusted, Q-9; {@code
     * DELIVERY_FAILED_NOT_REVERSED}: a correction of it is at the provider, or an adjustment took a line below what it
     * invoiced). Both null when there was nothing to reduce (not issued, or reversed already).
     */
    record Reversal(EInvoice.Snapshot drawn, EInvoiceNeedsAttention.Cause notReversed) {

        static final Reversal NOTHING = new Reversal(null, null);
    }

    /**
     * The reduction of what the issued invoice standing for a shipment still invoices, and the mark that it no longer
     * stands for it (WF-05 HĐ-3, BE-11). Under the order's lock and the invoice's; the same for the system, hearing of
     * the failure, and for the accountant's command ({@link #reduceForFailedDelivery}), so the two never decide
     * differently.
     * <ul>
     *   <li><b>A replacement</b> is never adjusted (Q-9): nothing is drawn up, and the accountants settle the failure on
     *       the provider's portal and record it.</li>
     *   <li><b>Corrections not issued yet</b> - a draft or refused adjustment naming it, a replacement being drawn up -
     *       are voided first: nothing was delivered for them to correct (BE-11). One at the provider, or a draft that
     *       lists it among others (a trade discount), is waited for: nothing is drawn up until it is settled, and the
     *       accountant runs the command again.</li>
     *   <li><b>Issued adjustments</b> no longer stop it (BE-11): what the invoice still comes to, line by line, is taken
     *       off ({@link EInvoice#drawUpDeliveryReversal(UUID, String, EInvoice, String, LocalDate, EInvoice.Corrections,
     *       List)}), and the invoice is always marked reversed, so a shipment delivered again takes its own original.</li>
     * </ul>
     */
    private Reversal reverse(EInvoice inForce) {
        if (inForce.status() != EInvoiceStatus.ISSUED || inForce.type() == EInvoiceType.ADJUSTMENT
                || inForce.reversedAt() != null) {
            return Reversal.NOTHING;
        }
        if (inForce.type() == EInvoiceType.REPLACEMENT) {
            return new Reversal(null, EInvoiceNeedsAttention.Cause.REPLACEMENT_NOT_REDUCIBLE);
        }
        List<EInvoice> unissued = invoices.findUnissuedCorrectionsOf(inForce.id());
        for (EInvoice correction : unissued) {
            if (correction.status() == EInvoiceStatus.SUBMITTING
                    || !inForce.id().equals(correction.originalEInvoiceId())) {
                return new Reversal(null, EInvoiceNeedsAttention.Cause.DELIVERY_FAILED_NOT_REVERSED);
            }
        }
        List<EInvoiceLine> issuedLines = invoices.issuedAdjustmentLinesOf(inForce.id());
        BigDecimal issuedTotal = issuedLines.stream().map(line -> line.amount().add(line.taxAmount()))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        // What corrects it once the unissued corrections are voided: only the issued adjustments.
        EInvoice.Corrections afterVoiding = new EInvoice.Corrections(!issuedLines.isEmpty() || inForce.adjusted(),
                false, issuedTotal);
        Instant now = clock.instant();
        LocalDate today = BusinessCalendar.date(now);
        ResourceBundle t = ResourceBundle.getBundle("i18n.messages", Locale.forLanguageTag("vi"));
        String shipmentCode = inForce.snapshot().shipmentCode();
        String reason = t.getString("einvoice.correction.deliveryFailed").formatted(inForce.code(),
                shipmentCode == null ? "-" : shipmentCode);
        EInvoice reversal;
        try {
            // Decided before anything is voided: a reduction that cannot be drawn up leaves every draft as it was.
            reversal = EInvoice.drawUpDeliveryReversal(Identifiers.newId(), invoices.nextCode(today), inForce,
                    reason.substring(0, Math.min(500, reason.length())), today, afterVoiding, issuedLines);
        } catch (BusinessException notReducible) {
            log.warn("E-invoice {} of a failed delivery is not reduced by the system: {}",
                    LogSafe.text(inForce.code()), notReducible.errorCode());
            return new Reversal(null, EInvoiceNeedsAttention.Cause.DELIVERY_FAILED_NOT_REVERSED);
        }
        for (EInvoice correction : unissued) {
            voidUnissued(invoices.findByIdForUpdate(correction.id()).orElseThrow());
        }
        EInvoice.Snapshot drawn = invoices.save(reversal).snapshot();
        // After the reduction is written: tg_einvoice_guard lets an invoice be reversed only while one names it.
        invoices.markReversed(inForce.id(), now);
        return new Reversal(drawn, null);
    }

    /**
     * Voids a draft, or a refused invoice, of a delivery corrected to a failure. A refused one goes back to a draft
     * first, saved on its own: the database lets an invoice go from REJECTED to DRAFT and from DRAFT to VOIDED_DRAFT,
     * never straight from one to the other ({@code tg_einvoice_guard}), and one save would be one UPDATE.
     */
    private void voidUnissued(EInvoice invoice) {
        voidUnissued(invoice, "The delivery was corrected to a failure: nothing was delivered");
    }

    private void voidUnissued(EInvoice invoice, String reason) {
        EInvoice draft = invoice;
        if (draft.status() == EInvoiceStatus.REJECTED) {
            draft.reopen();
            draft = invoices.save(draft);
        }
        draft.voidDraft(reason, null, clock.instant());
        invoices.save(draft);
    }

    private EInvoice lock(UUID id, long expectedVersion) {
        EInvoice invoice = invoices.findByIdForUpdate(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.EINVOICE_NOT_FOUND));
        if (invoice.version() != expectedVersion) {
            throw new BusinessException(ErrorCode.OPTIMISTIC_LOCK,
                    "Invoice %s changed since it was read".formatted(invoice.code()));
        }
        return invoice;
    }

    /**
     * The buyer as its legal profile names it; for a customer without one, what its record has - the company name, the
     * tax code and the contact e-mail, which is the invoice e-mail's default - and no address.
     */
    /**
     * Where the customer's e-invoices are sent now: the invoice e-mail of its legal profile, else its contact e-mail -
     * the address a draft drawn up now would carry ({@link #buyerOf}). Null when it has none, or the customer is gone.
     * Read when an invoice issued without an address is mailed, or mailed again (audit r2 M-1).
     */
    public String currentInvoiceEmail(UUID customerId) {
        if (customerId == null) {
            return null;
        }
        try {
            String email = buyerOf(customerId).email();
            return email == null || email.isBlank() ? null : email.strip();
        } catch (BusinessException gone) {
            return null;
        }
    }

    private InvoiceParty buyerOf(UUID customerId) {
        Optional<CustomerLegalProfile> profile = legalProfiles.legalProfile(customerId);
        if (profile.isPresent()) {
            CustomerLegalProfile p = profile.get();
            return new InvoiceParty(p.companyName(), p.taxCode(), p.registeredAddress(), p.invoiceEmail());
        }
        CustomerSummary customer = customers.findById(customerId)
                .orElseThrow(() -> new BusinessException(ErrorCode.CUSTOMER_NOT_FOUND));
        return new InvoiceParty(customer.fullName(), customer.taxCode(), null, customer.email());
    }

    private record Lines(List<EInvoiceLine> lines, boolean rateDiffersFromContract, boolean rateNotOnContract,
                         boolean taxClassUnknown) {
    }

    /** The goods of the delivery, then the fees this invoice carries given what the order's invoices carry already. */
    private static Lines lines(DeliveredShipment delivered, FeeAllocation.Invoiced invoiced) {
        ResourceBundle t = ResourceBundle.getBundle("i18n.messages", Locale.forLanguageTag("vi"));
        List<EInvoiceLine> lines = new ArrayList<>();
        boolean differs = false;
        boolean notOnContract = false;
        boolean classUnknown = false;
        for (DeliveredShipment.Line goods : delivered.lines()) {
            boolean cartons = goods.cartons() != null;
            lines.add(EInvoiceLine.goods(Identifiers.newId(), lines.size() + 1, goods.orderLineId(), goods.sku(),
                    goods.name(), t.getString(cartons ? "einvoice.unit.carton" : "einvoice.unit.piece"),
                    cartons ? goods.cartons() : goods.units(), cartons ? goods.unitsPerCarton() : null,
                    cartons ? goods.unitPrice().multiply(BigDecimal.valueOf(goods.unitsPerCarton())) : goods.unitPrice(),
                    goods.taxRate()));
            differs |= differs(goods.taxRate(), goods.contractTaxRate());
            notOnContract |= goods.contractTaxRate() == null;
            classUnknown |= goods.taxClassUnknown();
        }
        if (lines.isEmpty()) {
            return new Lines(lines, false, false, false);
        }
        for (DeliveredShipment.Fee fee : delivered.fees()) {
            BigDecimal amount;
            String name;
            if (fee.kind() == FeeKind.PLATE_FEE) {
                amount = FeeAllocation.carriesPlateFee(fee.sku(), invoiced) ? fee.amount() : BigDecimal.ZERO;
                name = t.getString("einvoice.line.plateFee").formatted(fee.sku());
            } else {
                // Q-19: the share waived for shipments that came back is not invoiced - payment does not collect it -
                // and the rest is shared by the goods' value, as payment waives it, so no invoice carries more than
                // is collected; in pieces only for a caller that gives no value.
                BigDecimal owed = FeeAllocation.shippingFeeOwed(fee.amount(), delivered.shippingFeeWaiver().feeGross(),
                        delivered.shippingFeeWaiver().waived());
                amount = delivered.orderGoodsValue() != null
                        ? FeeAllocation.shippingShareByValue(owed, delivered.orderGoodsValue(),
                                delivered.goodsValue(), invoiced)
                        : FeeAllocation.shippingShare(owed, delivered.orderUnits(), delivered.unitsInShipment(),
                                invoiced);
                name = delivered.shipmentCode() == null ? t.getString("einvoice.line.shippingFee.plain")
                        : t.getString("einvoice.line.shippingFee").formatted(delivered.shipmentCode());
            }
            if (amount.signum() <= 0) {
                continue;
            }
            lines.add(EInvoiceLine.fee(Identifiers.newId(), lines.size() + 1,
                    EInvoiceLine.FeeKind.valueOf(fee.kind().name()), fee.sku(), name, t.getString("einvoice.unit.fee"),
                    amount, fee.taxRate()));
            differs |= differs(fee.taxRate(), fee.contractTaxRate());
            notOnContract |= fee.contractTaxRate() == null;
            classUnknown |= fee.taxClassUnknown();
        }
        return new Lines(lines, differs, notOnContract, classUnknown);
    }

    private static boolean differs(BigDecimal rate, BigDecimal contractRate) {
        return contractRate != null && rate.compareTo(contractRate) != 0;
    }

    static String limited(String value, String field, int max) {
        String stripped = value.strip();
        if (stripped.length() > max) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, field + ": at most " + max + " characters");
        }
        return stripped;
    }

    /** Blank clears it; otherwise the canonical form the customer module keeps: 10 digits, 10-3, or 12. */
    static String taxCode(String entered) {
        if (entered.isBlank()) {
            return null;
        }
        String compact = entered.strip().replaceAll("[\\s.]", "");
        if (compact.matches("\\d{13}")) {
            compact = compact.substring(0, 10) + "-" + compact.substring(10);
        }
        if (!TAX_CODE.matcher(compact).matches()) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "buyerTaxCode: 10 digits, 10-3 for a dependent unit, or 12 digits for a household business");
        }
        return compact;
    }

    static String email(String entered) {
        if (entered.isBlank()) {
            return null;
        }
        String value = entered.strip().toLowerCase(Locale.ROOT);
        if (value.length() > 320 || !EMAIL.matcher(value).matches()) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "buyerEmail: not an e-mail address");
        }
        return value;
    }
}
