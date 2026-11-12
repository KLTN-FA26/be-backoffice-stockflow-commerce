package com.stockflow.invoicing.internal.domain;

import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * One VAT e-invoice (WF-05 E2, plan §1.4-§1.5; kltn-docs 21; NĐ 254/2026/NĐ-CP, TT 91/2026/TT-BTC).
 *
 * <p><b>Date and number.</b> An invoice's date is the day of its delivery (NĐ 254 Điều 9), fixed when it is drawn up;
 * its number is the provider's, given when it is issued. A draft issued late therefore takes a later number than an
 * invoice dated after it. The law asks for numbers that run on within a symbol (Phụ lục điểm 2) and for signing by the
 * next working day (điểm 7), which is why a draft carries a deadline; whether a real provider refuses a date before its
 * last number is a question for its adapter (package E3).</p>
 *
 * <p>An original invoices one delivered shipment. It is drawn up as a draft the moment the delivery is recorded, with
 * the seller and the buyer <b>copied</b> onto it and its lines and totals fixed from the order's prices. From then on:</p>
 * <ul>
 *   <li><b>A draft</b> takes two corrections - the buyer (refreshed from the customer's profile, or typed by the
 *       accountant) and the names on its lines. Its money never changes: quantities and prices are the contract's,
 *       and a draft that got them wrong is voided and drawn up again from the order.</li>
 *   <li><b>Issuing</b> needs a complete buyer (name, tax code, address, e-mail) and a series for the invoice's year.
 *       It gives the invoice a transaction id - its own id and the attempt's number - under which the provider knows
 *       at most one invoice. Every later question to the provider is asked under that id, so a reply that was lost can
 *       never turn into a second invoice for the same delivery.</li>
 *   <li><b>Issued is final.</b> The number, the tax authority's code, the files and everything the invoice says stay
 *       as they are; there is no cancelling (TT 91 Điều 10 has no such procedure). A wrong invoice is adjusted or
 *       replaced by another one naming it (package E4).</li>
 *   <li><b>Rejected</b> means no invoice exists at the provider for that transaction. The invoice goes back to a draft
 *       to be corrected, and its next attempt is a new transaction.</li>
 * </ul>
 *
 * <p>Each rule is also a constraint or trigger of {@code invoicing.einvoice}: the aggregate gives the message, the
 * database is what a manual UPDATE cannot talk its way past.</p>
 */
public final class EInvoice {

    /**
     * What the accountant should look at. A draft with any of them is never issued automatically; an invoice at the
     * provider carries only {@link #UNKNOWN_TO_PROVIDER}.
     */
    public enum Warning {
        /** The buyer's name, tax code, address or invoice e-mail is missing: issuing is refused until it is there. */
        BUYER_PROFILE_INCOMPLETE,
        /** A line's VAT rate on the day of delivery is not the one the contract fixed: the totals differ from the contract's. */
        TAX_RATE_DIFFERS_FROM_CONTRACT,
        /**
         * A line's order fixed no VAT rate at all, so the rate on it was looked up for the day of delivery - or, for a
         * product without a tax class, assumed standard. The customer agreed to no rate: the accountant decides.
         */
        TAX_RATE_NOT_ON_CONTRACT,
        /**
         * Package E3: the invoice was handed to the provider, the provider says it never received the transaction, and
         * the time the provider remembers a transaction id has passed - so it is not sent again on its own (the same
         * id could become a second invoice). It waits for the accountant, who sends it as a new transaction once the
         * provider confirms it holds none. Gone when the invoice is issued or leaves the provider.
         */
        UNKNOWN_TO_PROVIDER
    }

    /** The rejection code of an invoice taken back from a provider that confirmed it holds no such transaction. */
    public static final String UNKNOWN_TO_PROVIDER_CODE = "UNKNOWN_TO_PROVIDER";

    /** How the customer pays: by bank transfer against the contract's installments (kltn-docs 15), always. */
    public static final String BANK_TRANSFER = "BANK_TRANSFER";
    public static final String VND = "VND";
    /** NĐ 254 Phụ lục điểm 2: an invoice number has at most 8 digits. */
    public static final int MAX_INVOICE_NUMBER = 99_999_999;

    /**
     * Everything an invoice is, as it is stored: what the persistence adapter reads an invoice from and writes it to.
     * A plain carrier - the rules are the aggregate's, and {@link EInvoice#restore} trusts a snapshot only because the
     * database holds the same rules.
     */
    public record Snapshot(UUID id, String code, EInvoiceType type, EInvoiceStatus status, boolean adjusted,
                           UUID orderId, String orderNumber, String contractNumber, UUID shipmentId,
                           String shipmentCode, UUID deliveryBatchId, UUID customerId, UUID originalEInvoiceId,
                           String provider, int attempt, String transactionId, String templateCode, String symbol,
                           Integer invoiceNumber, String taxAuthorityCode, String lookupCode, LocalDate issueDate,
                           Instant issueDueAt, Instant dueWarnedAt, Instant signedAt, InvoiceParty seller,
                           InvoiceParty buyer, String currency, String paymentMethod, List<EInvoiceLine> lines,
                           BigDecimal subtotal, BigDecimal taxTotal, BigDecimal grandTotal, String amountInWords,
                           Set<Warning> warnings, String adjustmentReason, String agreementFileKey, String xmlFileKey,
                           String pdfFileKey, String rejectionCode, String rejectionMessage, Instant submittedAt,
                           UUID submittedBy, Instant issuedAt, Instant voidedAt, UUID voidedBy, String voidReason,
                           long version) {
    }

    /** The delivery an original invoices, as the order module names it. */
    public record Delivery(UUID orderId, String orderNumber, String contractNumber, UUID shipmentId,
                           String shipmentCode, UUID deliveryBatchId, UUID customerId, LocalDate deliveredOn) {
    }

    private final UUID id;
    private final String code;
    private final EInvoiceType type;
    private EInvoiceStatus status;
    /** Set when an adjustment of it is issued (E4); nothing here sets it yet. */
    private final boolean adjusted;
    private final UUID orderId;
    private final String orderNumber;
    private final String contractNumber;
    private final UUID shipmentId;
    private final String shipmentCode;
    private final UUID deliveryBatchId;
    private final UUID customerId;
    private final UUID originalEInvoiceId;
    private String provider;
    private int attempt;
    private String transactionId;
    private String templateCode;
    private String symbol;
    private Integer invoiceNumber;
    private String taxAuthorityCode;
    private String lookupCode;
    private final LocalDate issueDate;
    private final Instant issueDueAt;
    private Instant dueWarnedAt;
    private Instant signedAt;
    private final InvoiceParty seller;
    private InvoiceParty buyer;
    private final String currency;
    private final String paymentMethod;
    private final List<EInvoiceLine> lines;
    private final BigDecimal subtotal;
    private final BigDecimal taxTotal;
    private final BigDecimal grandTotal;
    private final String amountInWords;
    private final Set<Warning> warnings;
    private final String adjustmentReason;
    private final String agreementFileKey;
    private String xmlFileKey;
    private String pdfFileKey;
    private String rejectionCode;
    private String rejectionMessage;
    private Instant submittedAt;
    private UUID submittedBy;
    private Instant issuedAt;
    private Instant voidedAt;
    private UUID voidedBy;
    private String voidReason;
    private final long version;

    private EInvoice(Snapshot s) {
        this.id = Objects.requireNonNull(s.id(), "id");
        this.code = Objects.requireNonNull(s.code(), "code");
        this.type = Objects.requireNonNull(s.type(), "type");
        this.status = Objects.requireNonNull(s.status(), "status");
        this.adjusted = s.adjusted();
        this.orderId = Objects.requireNonNull(s.orderId(), "orderId");
        this.orderNumber = Objects.requireNonNull(s.orderNumber(), "orderNumber");
        this.contractNumber = s.contractNumber();
        this.shipmentId = s.shipmentId();
        this.shipmentCode = s.shipmentCode();
        this.deliveryBatchId = s.deliveryBatchId();
        this.customerId = Objects.requireNonNull(s.customerId(), "customerId");
        this.originalEInvoiceId = s.originalEInvoiceId();
        this.provider = s.provider();
        this.attempt = s.attempt();
        this.transactionId = s.transactionId();
        this.templateCode = s.templateCode();
        this.symbol = s.symbol();
        this.invoiceNumber = s.invoiceNumber();
        this.taxAuthorityCode = s.taxAuthorityCode();
        this.lookupCode = s.lookupCode();
        this.issueDate = Objects.requireNonNull(s.issueDate(), "issueDate");
        this.issueDueAt = Objects.requireNonNull(s.issueDueAt(), "issueDueAt");
        this.dueWarnedAt = s.dueWarnedAt();
        this.signedAt = s.signedAt();
        this.seller = Objects.requireNonNull(s.seller(), "seller");
        this.buyer = Objects.requireNonNull(s.buyer(), "buyer");
        this.currency = Objects.requireNonNull(s.currency(), "currency");
        this.paymentMethod = Objects.requireNonNull(s.paymentMethod(), "paymentMethod");
        List<EInvoiceLine> ordered = new ArrayList<>(s.lines());
        ordered.sort(Comparator.comparingInt(EInvoiceLine::lineNo));
        this.lines = ordered;
        this.subtotal = s.subtotal();
        this.taxTotal = s.taxTotal();
        this.grandTotal = s.grandTotal();
        this.amountInWords = s.amountInWords();
        this.warnings = s.warnings() == null || s.warnings().isEmpty() ? EnumSet.noneOf(Warning.class)
                : EnumSet.copyOf(s.warnings());
        this.adjustmentReason = s.adjustmentReason();
        this.agreementFileKey = s.agreementFileKey();
        this.xmlFileKey = s.xmlFileKey();
        this.pdfFileKey = s.pdfFileKey();
        this.rejectionCode = s.rejectionCode();
        this.rejectionMessage = s.rejectionMessage();
        this.submittedAt = s.submittedAt();
        this.submittedBy = s.submittedBy();
        this.issuedAt = s.issuedAt();
        this.voidedAt = s.voidedAt();
        this.voidedBy = s.voidedBy();
        this.voidReason = s.voidReason();
        this.version = s.version();
    }

    /**
     * Draws up the original invoice of a delivered shipment, as a draft: dated the day of the delivery (NĐ 254 Điều 9
     * khoản 1 - the day the goods changed hands, whenever the invoice is issued), due by the end of the next working
     * day ({@link IssueDeadline}), its totals added up from {@code lines}.
     *
     * @param taxRateDiffersFromContract whether a line carries a VAT rate other than the one its order line fixed
     * @param taxRateNotOnContract       whether a line's order fixed no VAT rate at all
     * @throws IllegalArgumentException for a seller missing its name, tax code or address, no lines, two lines with one
     *                                  number, or a line below zero - none of which a delivery produces
     */
    public static EInvoice drawUpOriginal(UUID id, String code, Delivery delivery, InvoiceParty seller,
                                          InvoiceParty buyer, List<EInvoiceLine> lines,
                                          boolean taxRateDiffersFromContract, boolean taxRateNotOnContract) {
        Objects.requireNonNull(delivery, "delivery");
        Objects.requireNonNull(delivery.shipmentId(), "An original invoices a shipment");
        if (seller == null || seller.name() == null || seller.taxCode() == null || seller.address() == null) {
            throw new IllegalArgumentException("An invoice names its seller: name, tax code and address");
        }
        if (lines == null || lines.isEmpty()) {
            throw new IllegalArgumentException("An invoice has at least one line");
        }
        Set<Integer> numbers = new HashSet<>();
        BigDecimal net = BigDecimal.ZERO;
        BigDecimal tax = BigDecimal.ZERO;
        for (EInvoiceLine line : lines) {
            if (!numbers.add(line.lineNo())) {
                throw new IllegalArgumentException("Two lines are numbered " + line.lineNo());
            }
            if (line.quantity() < 0) {
                throw new IllegalArgumentException("An original invoice has no line below zero");
            }
            net = net.add(line.amount());
            tax = tax.add(line.taxAmount());
        }
        BigDecimal total = net.add(tax);
        Set<Warning> warnings = EnumSet.noneOf(Warning.class);
        if (!buyer.completeBuyer()) {
            warnings.add(Warning.BUYER_PROFILE_INCOMPLETE);
        }
        if (taxRateDiffersFromContract) {
            warnings.add(Warning.TAX_RATE_DIFFERS_FROM_CONTRACT);
        }
        if (taxRateNotOnContract) {
            warnings.add(Warning.TAX_RATE_NOT_ON_CONTRACT);
        }
        return new EInvoice(new Snapshot(id, code, EInvoiceType.ORIGINAL, EInvoiceStatus.DRAFT, false,
                delivery.orderId(), delivery.orderNumber(), delivery.contractNumber(), delivery.shipmentId(),
                delivery.shipmentCode(), delivery.deliveryBatchId(), delivery.customerId(), null, null, 0, null, null,
                null, null, null, null, delivery.deliveredOn(), IssueDeadline.of(delivery.deliveredOn()), null, null,
                seller, buyer, VND, BANK_TRANSFER, lines, net, tax, total, VietnameseAmountInWords.of(total),
                warnings, null, null, null, null, null, null, null, null, null, null, null, null, 0));
    }

    /** An invoice as it was stored. */
    public static EInvoice restore(Snapshot snapshot) {
        return new EInvoice(snapshot);
    }

    // ---- corrections on a draft ---------------------------------------------------------------------------------

    /**
     * The buyer as the invoice is to name it: refreshed from the customer's profile, or corrected by the accountant.
     * Only on a draft - an issued invoice keeps the buyer it was issued to (kltn-docs 21 BR-06).
     */
    public void reviseBuyer(InvoiceParty revised) {
        requireDraft("change the buyer of");
        Objects.requireNonNull(revised, "buyer");
        if (revised.name() == null) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "buyer.name: required");
        }
        this.buyer = revised;
        if (revised.completeBuyer()) {
            warnings.remove(Warning.BUYER_PROFILE_INCOMPLETE);
        } else {
            warnings.add(Warning.BUYER_PROFILE_INCOMPLETE);
        }
    }

    /**
     * Other names for some lines, by line id: how the goods are called on the invoice (NĐ 254 Điều 10: in Vietnamese).
     * Nothing else about a line changes - its quantity, price and VAT are the contract's.
     *
     * @throws BusinessException {@code EINVOICE_NOT_DRAFT}; {@code VALIDATION_FAILED} for a line the invoice does not
     *                           have or a blank name
     */
    public void renameLines(Map<UUID, String> names) {
        requireDraft("change the lines of");
        if (names == null || names.isEmpty()) {
            return;
        }
        for (Map.Entry<UUID, String> entry : names.entrySet()) {
            int at = -1;
            for (int i = 0; i < lines.size(); i++) {
                if (lines.get(i).id().equals(entry.getKey())) {
                    at = i;
                }
            }
            String name = entry.getValue();
            if (at < 0 || name == null || name.isBlank() || name.strip().length() > 500) {
                throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                        "lines: %s is not a line of this invoice, or its name is blank or longer than 500"
                                .formatted(entry.getKey()));
            }
            lines.set(at, lines.get(at).renamed(name));
        }
    }

    // ---- lifecycle ----------------------------------------------------------------------------------------------

    /**
     * Sends the draft on its way to the provider: DRAFT → SUBMITTING, under a new transaction id.
     *
     * @param by who issued it; null when it was issued automatically
     * @throws BusinessException {@code EINVOICE_INVALID_TRANSITION} unless it is a draft;
     *                           {@code EINVOICE_BUYER_PROFILE_INCOMPLETE} while the buyer's name, tax code, address or
     *                           invoice e-mail is missing; {@code EINVOICE_SERIES_NOT_CONFIGURED} for a series of
     *                           another year than the invoice's date
     */
    public void submit(InvoiceSeries series, UUID by, Instant now) {
        requireCanBecome(EInvoiceStatus.SUBMITTING);
        if (!buyer.completeBuyer()) {
            throw new BusinessException(ErrorCode.EINVOICE_BUYER_PROFILE_INCOMPLETE,
                    "Invoice %s: the buyer's name, tax code, address and invoice e-mail are all needed".formatted(code));
        }
        Objects.requireNonNull(series, "series");
        if (series.year() != issueDate.getYear()) {
            throw new BusinessException(ErrorCode.EINVOICE_SERIES_NOT_CONFIGURED,
                    "Invoice %s is dated %d; series %s is of %d".formatted(code, issueDate.getYear(), series.symbol(),
                            series.year()));
        }
        this.status = EInvoiceStatus.SUBMITTING;
        this.provider = series.provider();
        this.templateCode = series.templateCode();
        this.symbol = series.symbol();
        this.attempt = attempt + 1;
        // One transaction per attempt: stable for every retry and question of this attempt, new only after a rejection,
        // when the provider is known to hold no invoice for the old one.
        this.transactionId = id + "-" + attempt;
        this.submittedAt = Objects.requireNonNull(now, "now");
        this.submittedBy = by;
        this.rejectionCode = null;
        this.rejectionMessage = null;
    }

    /**
     * The provider issued it: SUBMITTING → ISSUED, with the number it gave, the tax authority's code, the code the
     * buyer looks it up by, when it was signed, and the two files as stored.
     */
    public void issued(int number, String taxAuthorityCode, String lookupCode, Instant signedAt, String xmlFileKey,
                       String pdfFileKey, Instant now) {
        requireCanBecome(EInvoiceStatus.ISSUED);
        if (number < 1 || number > MAX_INVOICE_NUMBER) {
            throw new IllegalArgumentException("An invoice number is 1 to " + MAX_INVOICE_NUMBER + ", not " + number);
        }
        this.status = EInvoiceStatus.ISSUED;
        this.invoiceNumber = number;
        this.taxAuthorityCode = requireText(taxAuthorityCode, "taxAuthorityCode");
        this.lookupCode = requireText(lookupCode, "lookupCode");
        this.signedAt = Objects.requireNonNull(signedAt, "signedAt");
        this.xmlFileKey = requireText(xmlFileKey, "xmlFileKey");
        this.pdfFileKey = requireText(pdfFileKey, "pdfFileKey");
        this.issuedAt = Objects.requireNonNull(now, "now");
        // Whatever was worth a look is settled by the invoice having been issued.
        this.warnings.clear();
    }

    /**
     * Marks an invoice at the provider as one the provider does not know and that is no longer sent on its own.
     *
     * @return whether it was not marked before
     */
    public boolean flagUnknownToProvider() {
        if (status != EInvoiceStatus.SUBMITTING) {
            throw new BusinessException(ErrorCode.EINVOICE_INVALID_TRANSITION,
                    "Invoice %s is %s, not at the provider".formatted(code, status));
        }
        return warnings.add(Warning.UNKNOWN_TO_PROVIDER);
    }

    /** The provider or the tax authority refused it: SUBMITTING → REJECTED, with its reason. */
    public void rejected(String rejectionCode, String rejectionMessage) {
        requireCanBecome(EInvoiceStatus.REJECTED);
        this.rejectionCode = requireText(rejectionCode, "rejectionCode");
        this.status = EInvoiceStatus.REJECTED;
        this.rejectionMessage = rejectionMessage == null ? null
                : rejectionMessage.substring(0, Math.min(500, rejectionMessage.length()));
        // It has left the provider: what was worth a look there is settled. Left on, it would also keep the draft it
        // becomes from being issued automatically.
        this.warnings.remove(Warning.UNKNOWN_TO_PROVIDER);
    }

    /** Back to a draft to be corrected: REJECTED → DRAFT. What was refused stays on it until it is issued again. */
    public void reopen() {
        move(EInvoiceStatus.DRAFT);
    }

    /**
     * Gives up a draft that was never sent: DRAFT → VOIDED_DRAFT. Not for anything that reached the provider, and
     * never for an issued invoice - that is adjusted or replaced, not voided.
     *
     * @param by null when the system voids it
     */
    public void voidDraft(String reason, UUID by, Instant now) {
        if (reason == null || reason.isBlank()) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "reason: required to void a draft");
        }
        move(EInvoiceStatus.VOIDED_DRAFT);
        this.voidReason = reason.strip().substring(0, Math.min(500, reason.strip().length()));
        this.voidedBy = by;
        this.voidedAt = Objects.requireNonNull(now, "now");
    }

    /** The accountant was told its deadline is near; told once. */
    public void warnedOfDeadline(Instant now) {
        if (dueWarnedAt == null) {
            this.dueWarnedAt = now;
        }
    }

    /** Whether it still waits to be issued past its deadline ({@link IssueDeadline}). */
    public boolean overdue(Instant now) {
        return !status.wasIssued() && status != EInvoiceStatus.VOIDED_DRAFT && !now.isBefore(issueDueAt);
    }

    /** What each VAT rate comes to on this invoice (NĐ 254 Điều 10 khoản 1 điểm đ: tax by rate), lowest rate first. */
    public List<TaxRateTotal> totalsByRate() {
        return TaxRateTotal.of(lines);
    }

    private void requireDraft(String what) {
        if (status != EInvoiceStatus.DRAFT) {
            throw new BusinessException(ErrorCode.EINVOICE_NOT_DRAFT,
                    "Cannot %s invoice %s: it is %s, and only a draft changes".formatted(what, code, status));
        }
    }

    private void move(EInvoiceStatus target) {
        requireCanBecome(target);
        this.status = target;
    }

    private void requireCanBecome(EInvoiceStatus target) {
        if (!status.canTransitionTo(target)) {
            throw new BusinessException(ErrorCode.EINVOICE_INVALID_TRANSITION,
                    "Invoice %s is %s and cannot become %s".formatted(code, status, target));
        }
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " is required");
        }
        return value;
    }

    public Snapshot snapshot() {
        return new Snapshot(id, code, type, status, adjusted, orderId, orderNumber, contractNumber, shipmentId,
                shipmentCode, deliveryBatchId, customerId, originalEInvoiceId, provider, attempt, transactionId,
                templateCode, symbol, invoiceNumber, taxAuthorityCode, lookupCode, issueDate, issueDueAt, dueWarnedAt,
                signedAt, seller, buyer, currency, paymentMethod, List.copyOf(lines), subtotal, taxTotal, grandTotal,
                amountInWords, Set.copyOf(warnings), adjustmentReason, agreementFileKey, xmlFileKey, pdfFileKey,
                rejectionCode, rejectionMessage, submittedAt, submittedBy, issuedAt, voidedAt, voidedBy, voidReason,
                version);
    }

    public UUID id() { return id; }
    public String code() { return code; }
    public EInvoiceType type() { return type; }
    public EInvoiceStatus status() { return status; }
    public UUID orderId() { return orderId; }
    public UUID shipmentId() { return shipmentId; }
    public UUID customerId() { return customerId; }
    public String provider() { return provider; }
    public String transactionId() { return transactionId; }
    public String templateCode() { return templateCode; }
    public String symbol() { return symbol; }
    public LocalDate issueDate() { return issueDate; }
    public Instant issueDueAt() { return issueDueAt; }
    public InvoiceParty buyer() { return buyer; }
    public List<EInvoiceLine> lines() { return List.copyOf(lines); }
    public Set<Warning> warnings() { return Set.copyOf(warnings); }
    public String xmlFileKey() { return xmlFileKey; }
    public String pdfFileKey() { return pdfFileKey; }
    public long version() { return version; }
}
