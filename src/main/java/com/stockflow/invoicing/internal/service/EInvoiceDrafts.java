package com.stockflow.invoicing.internal.service;

import com.stockflow.common.audit.AuditAction;
import com.stockflow.common.audit.Auditable;
import com.stockflow.common.config.SellerProperties;
import com.stockflow.common.domain.BusinessCalendar;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.id.Identifiers;
import com.stockflow.customer.api.CustomerLegalProfile;
import com.stockflow.customer.api.CustomerLegalProfiles;
import com.stockflow.customer.api.CustomerService;
import com.stockflow.customer.api.CustomerSummary;
import com.stockflow.invoicing.internal.domain.EInvoice;
import com.stockflow.invoicing.internal.domain.EInvoiceLine;
import com.stockflow.invoicing.internal.domain.EInvoiceRepository;
import com.stockflow.invoicing.internal.domain.EInvoiceStatus;
import com.stockflow.invoicing.internal.domain.FeeAllocation;
import com.stockflow.invoicing.internal.domain.InvoiceParty;
import com.stockflow.order.api.DeliveredShipment;
import com.stockflow.order.api.DeliveredShipments;
import com.stockflow.order.api.FeeKind;
import com.stockflow.order.api.OrderShippingFee;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
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
 * <p>A shipment has one original invoice in force. Asking again for a shipment that has one returns it. Drafts of one
 * order are drawn up one after the other, under the order's invoicing lock: the second sees the first, and the fees it
 * carries. {@code uk_einvoice_original_per_shipment} is the last line of defence.</p>
 */
@Service
public class EInvoiceDrafts {

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
    private final Clock clock;

    EInvoiceDrafts(EInvoiceRepository invoices, DeliveredShipments shipments, CustomerLegalProfiles legalProfiles,
                   CustomerService customers, SellerProperties seller, Clock clock) {
        this.invoices = invoices;
        this.shipments = shipments;
        this.legalProfiles = legalProfiles;
        this.customers = customers;
        this.seller = seller;
        this.clock = clock;
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
     * @throws BusinessException {@code EINVOICE_NOT_INVOICEABLE} when the shipment is not delivered (or its delivery
     *         was corrected to a failure), delivered nothing, is not priced in dong, or had its invoice replaced;
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
        LocalDate deliveredOn = BusinessCalendar.date(
                delivered.deliveredAt() == null ? clock.instant() : delivered.deliveredAt());
        EInvoice invoice = EInvoice.drawUpOriginal(Identifiers.newId(), invoices.nextCode(deliveredOn),
                new EInvoice.Delivery(delivered.orderId(), delivered.orderNumber(), delivered.contractNumber(),
                        delivered.shipmentId(), delivered.shipmentCode(), delivered.deliveryBatchId(),
                        delivered.customerId(), deliveredOn),
                new InvoiceParty(seller.name(), seller.taxCode(), seller.address(), null),
                buyerOf(delivered.customerId()), lines.lines(), lines.rateDiffersFromContract(),
                lines.rateNotOnContract());
        return new DrawnUp(invoices.save(invoice).snapshot(), true);
    }

    /**
     * The invoice of the rest of an order's shipping fee, once its undelivered rest was closed: the one standing if
     * there is one, else a new draft when some of the fee is on no invoice and no delivery is left to carry it
     * ({@link FeeAllocation#shippingRest}); empty when there is nothing to draw up.
     *
     * <h2>Why an original of its own, and why dated the day of the last delivery</h2>
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
     * <p>It is dated the day of the order's last delivery ({@link OrderShippingFee#lastDeliveredAt}), and at that
     * day's VAT rate. A service is invoiced when it is completed (Điều 9 khoản 2), and the carrying the fee pays for
     * was completed then, not when the order's rest was closed - which may be weeks later and is an office decision,
     * not a service. The legal review (E0) does not say when a carriage billed apart from the goods counts as
     * completed; the last delivery is the date this code takes, and it is an open question for the business owner.
     * The price is that such an invoice is usually overdue the day it is drawn up: its deadline ({@code IssueDeadline},
     * the working day after its date) has passed, the accountants are warned of it as of any late draft, and it is
     * issued like any other. Only when no delivery has a time - an
     * order none of whose shipments was delivered - does it fall back to the day of closing.</p>
     *
     * <p>In its own transaction ({@code REQUIRES_NEW}) and under the order's invoicing lock, for the reasons
     * {@link #drawUp} gives: a delivery's draft drawn up at the same moment either sees this one's fee line or is seen
     * by it, so the fee is carried once, by whichever is decided second taking only what the first left.</p>
     *
     * @param closedAt when the order's rest was closed: the invoice's date and the day of its VAT rate only when no
     *                 delivery of the order has a time
     * @throws BusinessException {@code EINVOICE_NOT_INVOICEABLE} for an order not priced in dong;
     *         {@code SELLER_PROFILE_UNAVAILABLE} while the seller is not configured
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Optional<DrawnUp> drawUpShippingFeeRest(UUID orderId, Instant closedAt) {
        invoices.lockOrder(orderId);
        Optional<EInvoice> standing = invoices.findLiveShippingFeeInvoice(orderId);
        if (standing.isPresent()) {
            return Optional.of(new DrawnUp(standing.get().snapshot(), false));
        }
        Optional<OrderShippingFee> found = shipments.shippingFee(orderId, closedAt);
        if (found.isEmpty()) {
            return Optional.empty();
        }
        OrderShippingFee order = found.get();
        BigDecimal rest = FeeAllocation.shippingRest(order.fee().amount(), order.orderUnits(),
                invoices.invoicedOfOrder(orderId));
        if (rest.signum() <= 0) {
            return Optional.empty();
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
        DeliveredShipment.Fee fee = order.fee();
        EInvoiceLine line = EInvoiceLine.fee(Identifiers.newId(), 1, EInvoiceLine.FeeKind.SHIPPING_FEE, null,
                t.getString("einvoice.line.shippingFee.rest"), t.getString("einvoice.unit.fee"), rest, fee.taxRate());
        Instant completedAt = order.lastDeliveredAt() != null ? order.lastDeliveredAt()
                : closedAt != null ? closedAt : clock.instant();
        LocalDate completedOn = BusinessCalendar.date(completedAt);
        EInvoice invoice = EInvoice.drawUpOriginal(Identifiers.newId(), invoices.nextCode(completedOn),
                new EInvoice.Delivery(order.orderId(), order.orderNumber(), order.contractNumber(), null, null, null,
                        order.customerId(), completedOn),
                new InvoiceParty(seller.name(), seller.taxCode(), seller.address(), null),
                buyerOf(order.customerId()), List.of(line), differs(fee.taxRate(), fee.contractTaxRate()),
                fee.contractTaxRate() == null);
        return Optional.of(new DrawnUp(invoices.save(invoice).snapshot(), true));
    }

    /** The buyer copied again from the customer's legal profile, onto a draft (kltn-docs 21 §4.2 step 4). */
    @Transactional
    @Auditable(action = AuditAction.UPDATE, resourceType = "einvoice", resourceId = "#id")
    public EInvoice.Snapshot refreshBuyer(UUID id, long expectedVersion) {
        EInvoice invoice = lock(id, expectedVersion);
        invoice.reviseBuyer(buyerOf(invoice.customerId()));
        return invoices.save(invoice).snapshot();
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
        /** Its invoice is at the provider or issued: it cannot be voided, and is the accountant's to adjust (E4). */
        LEFT_FOR_ADJUSTMENT
    }

    /**
     * The delivery of a shipment was corrected to a failure (kltn-docs 09 §4.3 step 5; 21 §4.5): nothing was delivered,
     * so an invoice that was never issued for it is voided by the system, with the reason. A rejected one goes back to
     * a draft first - no invoice exists at the provider for it - and is voided the same way.
     *
     * <p>An invoice already at the provider, or issued, is not touched: it may exist in law, and an issued invoice is
     * only ever adjusted (21 §4.4: a full decrease), which is package E4's.</p>
     */
    @Transactional
    public CorrectionOutcome voidForCorrectedDelivery(UUID orderId, UUID shipmentId) {
        // The lock a draft is drawn up under: a draft being drawn up for this shipment right now is finished first,
        // and found here.
        invoices.lockOrder(orderId);
        Optional<EInvoice> inForce = invoices.findLiveOriginalOfShipment(shipmentId);
        if (inForce.isEmpty()) {
            return CorrectionOutcome.NO_INVOICE;
        }
        EInvoice invoice = invoices.findByIdForUpdate(inForce.get().id()).orElseThrow();
        if (invoice.status() == EInvoiceStatus.REJECTED) {
            invoice.reopen();
        }
        if (invoice.status() != EInvoiceStatus.DRAFT) {
            return CorrectionOutcome.LEFT_FOR_ADJUSTMENT;
        }
        invoice.voidDraft("The delivery was corrected to a failure: nothing was delivered", null, clock.instant());
        invoices.save(invoice);
        return CorrectionOutcome.VOIDED;
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

    private record Lines(List<EInvoiceLine> lines, boolean rateDiffersFromContract, boolean rateNotOnContract) {
    }

    /** The goods of the delivery, then the fees this invoice carries given what the order's invoices carry already. */
    private static Lines lines(DeliveredShipment delivered, FeeAllocation.Invoiced invoiced) {
        ResourceBundle t = ResourceBundle.getBundle("i18n.messages", Locale.forLanguageTag("vi"));
        List<EInvoiceLine> lines = new ArrayList<>();
        boolean differs = false;
        boolean notOnContract = false;
        for (DeliveredShipment.Line goods : delivered.lines()) {
            boolean cartons = goods.cartons() != null;
            lines.add(EInvoiceLine.goods(Identifiers.newId(), lines.size() + 1, goods.orderLineId(), goods.sku(),
                    goods.name(), t.getString(cartons ? "einvoice.unit.carton" : "einvoice.unit.piece"),
                    cartons ? goods.cartons() : goods.units(), cartons ? goods.unitsPerCarton() : null,
                    cartons ? goods.unitPrice().multiply(BigDecimal.valueOf(goods.unitsPerCarton())) : goods.unitPrice(),
                    goods.taxRate()));
            differs |= differs(goods.taxRate(), goods.contractTaxRate());
            notOnContract |= goods.contractTaxRate() == null;
        }
        if (lines.isEmpty()) {
            return new Lines(lines, false, false);
        }
        for (DeliveredShipment.Fee fee : delivered.fees()) {
            BigDecimal amount;
            String name;
            if (fee.kind() == FeeKind.PLATE_FEE) {
                amount = FeeAllocation.carriesPlateFee(fee.sku(), invoiced) ? fee.amount() : BigDecimal.ZERO;
                name = t.getString("einvoice.line.plateFee").formatted(fee.sku());
            } else {
                amount = FeeAllocation.shippingShare(fee.amount(), delivered.orderUnits(),
                        delivered.unitsInShipment(), invoiced);
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
        }
        return new Lines(lines, differs, notOnContract);
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
