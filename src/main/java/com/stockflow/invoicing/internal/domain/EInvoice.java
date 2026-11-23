package com.stockflow.invoicing.internal.domain;

import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.id.Identifiers;
import com.stockflow.common.text.VietnameseAmountInWords;

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
 * <p><b>Date and number.</b> An original's date is the day of its delivery (NĐ 254 Điều 9), set when it is drawn up;
 * a correction's is the day it is issued (WF-05 Q-1, {@link #dateForIssue}). Under a provider that takes only today's
 * date (SePay, {@code EInvoiceProvider.datesOnlyToday}) an original issued after its delivery day is dated the day it
 * is issued and flagged {@link Warning#ISSUED_LATE} - its delivery day stays on it ({@link #deliveredOn}) and is never
 * changed to match. Its number is the provider's, given when it is issued. The law asks for numbers that run on within a symbol in the
 * order of the dates (Phụ lục điểm 2) and for issuing on the invoice's own date (Điều 9 khoản 1; the next working day of
 * khoản 5 and Phụ lục điểm 7 is for night sales without automatic issuing, not for this system - owner, 23/11/2026),
 * which is why a draft carries a deadline ({@link IssueDeadline}). So an invoice is not sent while one of the same series dated earlier is at the provider, where it may
 * already hold the next number ({@code EInvoiceIssuanceSteps.submit}, {@code EINVOICE_OLDER_DRAFT_PENDING}); a draft
 * or a rejected invoice holds no number and keeps nothing back (HĐ-2) - one stuck invoice must not leave every later
 * one overdue - and is only named on the screen of a later draft. An invoice dated before others that were already
 * issued - a delivery recorded late, a draft issued after a later one - still goes out, with the next number and its
 * own date, since one that could never be issued would be worse. Whether a real provider refuses a date before its
 * last number is a question for its adapter (package E3).</p>
 *
 * <p>An original invoices one delivered shipment. It is drawn up as a draft the moment the delivery is recorded, with
 * the seller and the buyer <b>copied</b> onto it and its lines and totals fixed from the order's prices. From then on:</p>
 * <ul>
 *   <li><b>A draft</b> takes two corrections - the buyer (refreshed from the customer's profile, or typed by the
 *       accountant) and the names on its lines. Its money never changes: quantities and prices are the contract's,
 *       and a draft that got them wrong is voided and drawn up again from the order.</li>
 *   <li><b>Issuing</b> needs a complete buyer (name, tax code, address) with an e-mail that receives the invoice
 *       (kltn-docs 21 BR-06; HĐ-8) and a series for the invoice's year.
 *       It gives the invoice a transaction id - its own id and the attempt's number - under which the provider knows
 *       at most one invoice. Every later question to the provider is asked under that id, so a reply that was lost can
 *       never turn into a second invoice for the same delivery.</li>
 *   <li><b>Issued is final.</b> The number, the tax authority's code, the files and everything the invoice says stay
 *       as they are; there is no cancelling (TT 91 Điều 10 has no such procedure). A wrong invoice is adjusted or
 *       replaced by another one naming it; the only marks it then takes are "has an adjustment" and REPLACED.</li>
 *   <li><b>Rejected</b> means no invoice exists at the provider for that transaction. The invoice goes back to a draft
 *       to be corrected, and its next attempt is a new transaction.</li>
 * </ul>
 *
 * <h2>Corrections (WF-05 E4; TT 91 Điều 10)</h2>
 * <ul>
 *   <li><b>An adjustment</b> ({@link #drawUpAdjustment}) states what changes, signed: below zero reduces. The invoice it
 *       names stays in force and is flagged once the adjustment is issued. Its {@link CorrectionBasis} says why: an
 *       error, returned goods, a trade discount, or a delivery corrected to a failure - the last three only reduce,
 *       and the last, drawn up by the system alone ({@link #drawUpDeliveryReversal}), reduces the whole invoice.</li>
 *   <li><b>A replacement</b> ({@link #drawUpReplacement}) is the invoice as it should have been; the one it names
 *       becomes REPLACED as the replacement is issued.</li>
 *   <li><b>The form is kept</b> (khoản 6 điểm a): an invoice with an adjustment is not replaced, one being replaced is
 *       not adjusted, and an invoice that itself replaced another has its errors replaced again. Only an original or a
 *       replacement in force is corrected - an adjustment that is wrong is put right by another adjustment of the
 *       invoice it names.</li>
 *   <li><b>The agreement</b> (khoản 1 điểm b): a correction of an error is not sent before the written agreement of the
 *       two parties - its file, number and date - is on it. Every buyer here is a business (B2B only), so there is no
 *       exemption for an individual buyer.</li>
 * </ul>
 *
 * <p>Each rule is also a constraint or trigger of {@code invoicing.einvoice}: the aggregate gives the message, the
 * database is what a manual UPDATE cannot talk its way past.</p>
 */
public final class EInvoice {

    /**
     * What the accountant should look at. A draft with one that {@link #blocksAutomaticIssue() blocks} is never issued
     * automatically; an invoice at the provider carries only {@link #UNKNOWN_TO_PROVIDER} and {@link #ISSUED_LATE}.
     */
    public enum Warning {
        /** The buyer's name, tax code or address is missing: issuing is refused until it is there. */
        BUYER_PROFILE_INCOMPLETE,
        /**
         * HĐ-8: the buyer has no e-mail to receive the invoice. It blocks nothing - not the accountant's issuing, and
         * since WF-05 Q-22 (row BR-06) not the automatic issuing either: the e-mail is not invoice content in law (NĐ
         * 254/2026 Điều 10 khoản 1; {@code ck_einvoice_buyer_complete} does not ask for it), it is no longer sent to the
         * provider at all (Q-14: the system mails the buyer), and an original held back for it would miss the delivery
         * day it must be issued on (Q-1). Sales are told to add it ({@code EInvoiceBuyerIncomplete}); the customer
         * finds the invoice on the portal meanwhile.
         */
        BUYER_EMAIL_MISSING,
        /** A line's VAT rate on the day of delivery is not the one the contract fixed: the totals differ from the contract's. */
        TAX_RATE_DIFFERS_FROM_CONTRACT,
        /**
         * A line's order fixed no VAT rate at all, so the rate on it was looked up for the day of delivery - or, for a
         * product without a tax class, assumed standard. The customer agreed to no rate: the accountant decides.
         */
        TAX_RATE_NOT_ON_CONTRACT,
        /**
         * WF-05 Q-21 (BE-31): a line's product has no tax class, so the line was taxed at the highest rate, 10% (Luật
         * Thuế GTGT 48/2024/QH15 Điều 9 khoản 4: where the rate cannot be determined, the highest applies) - never the
         * rate the contract happened to carry, which silently went out at 8% after the reduction period. A draft drawn
         * up cannot change its rate, so the accountant gives the product its class, voids the draft and draws it up
         * again; it blocks the automatic issuing until then.
         */
        TAX_CLASS_UNKNOWN,
        /**
         * Found just before the automatic issuing (WF-05 review, defect 4): a line's rate is not the one a draft drawn
         * up now for the same delivery would carry - the draft was drawn up before a rate period that covers its day was
         * entered (the NĐ 174 reduction, V20261201000200), or the rates changed since. Warnings are worked out when a
         * draft is drawn up, so a draft older than a rule would otherwise be issued as the rule never existed. The
         * accountant voids it and draws it up again, or issues it as it is having checked why.
         */
        TAX_RATE_OUT_OF_DATE,
        /**
         * A line (not a note) is at 0%, and the invoice is not issued ({@code EINVOICE_TAX_EXEMPT_NOT_SUPPORTED}). No
         * tax class here is zero-rated: 0.00 is the rate {@code ordering.tax_rate} keeps for EXEMPT - goods not subject
         * to VAT ("không chịu thuế", KCT) - and an invoice saying 0% would declare zero-rated goods, which is another
         * thing in law. No provider adapter here can state KCT yet (SePay takes 0, 5, 8 and 10 only). So the accountant
         * corrects the product's tax class and draws the invoice up again, or invoices those goods outside the system.
         * Checked on the invoice, not on the product or the quote: no product is EXEMPT today, the class stays for the
         * tests and the QA checks, and the invoice is where every path a rate takes meets - a quote, an order, a
         * reduction period, a fee, a correction. The database says the same ({@code tg_einvoice_no_zero_rate_line}).
         */
        TAX_EXEMPT_NOT_SUPPORTED,
        /**
         * Package E3: the invoice was handed to the provider, the provider says it never received the transaction, and
         * the time the provider remembers a transaction id has passed - so it is not sent again on its own (the same
         * id could become a second invoice). It waits for the accountant, who sends it as a new transaction once the
         * provider confirms it holds none. Gone when the invoice is issued or leaves the provider.
         */
        UNKNOWN_TO_PROVIDER,
        /**
         * HĐ-8: the provider issued the invoice, but the seller's digital signature on the XML it handed back does not
         * hold ({@code InvoiceXmlSignature}: changed content, a signature its certificate did not make, none at all).
         * The invoice exists in law and stays issued; it is not sent to the buyer, and the accountant replaces it.
         * The only warning an issued invoice carries; set as it is issued, since an issued invoice changes no more and
         * its stored XML always checks the same.
         */
        SIGNATURE_INVALID,
        /**
         * WF-05 Q-1, Q-10: an original issued after the day of its delivery, under a provider that takes only today's
         * date - it carries the day it was issued, its delivery day stays on it ({@link EInvoice#deliveredOn}), and the
         * days between are how late it was ("Lập trễ N ngày"). Set as it is submitted, after the automatic issuing chose
         * it, so it never holds an invoice back - every hour waited would make it later. Kept once issued, so late
         * invoices can be counted ({@code ck_einvoice_issued_late} says the same).
         */
        ISSUED_LATE;

        /**
         * Whether a draft carrying it waits for the accountant instead of being issued automatically: everything that
         * changes what the invoice says or whether it can be issued. Not a missing e-mail (Q-22, BR-06) and not lateness.
         */
        public boolean blocksAutomaticIssue() {
            return this != BUYER_EMAIL_MISSING && this != ISSUED_LATE;
        }
    }

    /** The rejection code of an invoice taken back from a provider that confirmed it holds no such transaction. */
    public static final String UNKNOWN_TO_PROVIDER_CODE = "UNKNOWN_TO_PROVIDER";

    /**
     * The rejection code of an invoice the accountant took back from the provider after it was stuck there, the
     * provider confirming it has no record of it (HĐ-1, {@code EInvoiceIssuance.withdraw}): reopened and issued again
     * under a new transaction like any refused one. The accountant's reason is its rejection message.
     */
    public static final String WITHDRAWN_CODE = "WITHDRAWN_NO_PROVIDER_RECORD";

    /**
     * WF-05 BE-07 (Q-5): the rejection code of an invoice taken back while the provider still could not confirm what
     * became of it, on the written word of the provider's support - whose ticket or reply is its rejection message. The
     * old transaction is watched for 30 days afterwards, in case the provider issued it after all.
     */
    public static final String WITHDRAWN_RESULT_UNCONFIRMED_CODE = "WITHDRAWN_RESULT_UNCONFIRMED";

    /**
     * WF-05 BE-08 (Q-8): the rejection code of an invoice the accountant recorded as refused by the tax authority, as
     * the provider's portal shows it - the way out for an invoice the provider holds without a code for good.
     */
    public static final String REFUSED_BY_TAX_AUTHORITY_CODE = "REFUSED_BY_TAX_AUTHORITY";

    /** How an issued invoice came to be recorded (WF-05 Q-8). */
    public enum IssuedVia {
        /** The provider's answer recorded it. */
        PROVIDER,
        /**
         * The accountant recorded what the provider's portal shows: an invoice stuck at the provider (BE-08), or a
         * reduction made on the portal because no provider could send it (BE-10, Q-9).
         */
        PORTAL_RECORD
    }

    /** Why the reduction of a failed delivery - otherwise never voided - was voided (WF-05 Q-8, BE-29). */
    public enum VoidReasonCode {
        /** No provider can send it: the accountant makes it on the provider's portal and records it (BE-10). */
        UNSENDABLE_REVERSAL,
        /** The delivery was corrected back to delivered before it left: the system voids it (BE-29). */
        DELIVERY_CORRECTED_BACK
    }

    /** How the customer pays: by bank transfer against the contract's installments (kltn-docs 15), always. */
    public static final String BANK_TRANSFER = "BANK_TRANSFER";
    public static final String VND = "VND";
    /** NĐ 254 Phụ lục điểm 2: an invoice number has at most 8 digits. */
    public static final int MAX_INVOICE_NUMBER = 99_999_999;

    /**
     * Everything an invoice is, as it is stored: what the persistence adapter reads an invoice from and writes it to.
     * A plain carrier - the rules are the aggregate's, and {@link EInvoice#restore} trusts a snapshot only because the
     * database holds the same rules.
     *
     * <p>{@code deliveryFailedAt} (WF-05 HĐ-3) is carried for reading only: the mark is written and taken back in SQL
     * by {@link EInvoiceRepository#markDeliveryFailed} and {@link EInvoiceRepository#clearDeliveryFailed}, guarded by
     * {@code tg_einvoice_delivery_failed}, and nothing the aggregate does changes it. It is here so that every answer
     * built from a snapshot can show it; null for a new invoice and for one never marked. {@code reversedAt} likewise
     * ({@link EInvoiceRepository#markReversed}): read so that an invoice reduced in full for a failed delivery refuses
     * every other correction (BE-36) and its screen says so.</p>
     *
     * <p>{@code deliveredOn} (WF-05 Q-1): the day of the delivery an original invoices, set when it is drawn up and never
     * changed; null on a correction and on invoices from before. {@code issuedVia}, {@code portalRecordedBy},
     * {@code portalRecordedAt} (Q-8): how an issued invoice was recorded. {@code voidReasonCode}: why the reduction of
     * a failed delivery was voided.</p>
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
                           long version, CorrectionBasis correctionBasis, String agreementNumber,
                           LocalDate agreementDate, UUID returnRequestId, List<UUID> listedInvoiceIds,
                           Instant deliveryFailedAt, LocalDate deliveredOn, Instant reversedAt, IssuedVia issuedVia,
                           UUID portalRecordedBy, Instant portalRecordedAt, VoidReasonCode voidReasonCode) {

        public Snapshot {
            listedInvoiceIds = listedInvoiceIds == null ? List.of() : List.copyOf(listedInvoiceIds);
        }

        /**
         * Whether the buyer is sent this invoice (WF-05 E5, HĐ-8): issued - or replaced since, the buyer was issued it
         * all the same - and its seller's signature holds. One whose signature does not is announced to the
         * accountants and replaced, never mailed: "Chữ ký sai thì báo Kế toán ngay, không gửi khách hoá đơn lỗi"
         * (decision §3 HĐ-8). The one rule for the first mail and every mail sent again (audit r2 M-1).
         */
        public boolean sendableToBuyer() {
            return status.wasIssued() && !warnings.contains(Warning.SIGNATURE_INVALID);
        }

        /** As every snapshot was before WF-05: no delivery day, never reversed, issued through the provider. */
        public Snapshot(UUID id, String code, EInvoiceType type, EInvoiceStatus status, boolean adjusted,
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
                        long version, CorrectionBasis correctionBasis, String agreementNumber,
                        LocalDate agreementDate, UUID returnRequestId, List<UUID> listedInvoiceIds,
                        Instant deliveryFailedAt) {
            this(id, code, type, status, adjusted, orderId, orderNumber, contractNumber, shipmentId, shipmentCode,
                    deliveryBatchId, customerId, originalEInvoiceId, provider, attempt, transactionId, templateCode,
                    symbol, invoiceNumber, taxAuthorityCode, lookupCode, issueDate, issueDueAt, dueWarnedAt, signedAt,
                    seller, buyer, currency, paymentMethod, lines, subtotal, taxTotal, grandTotal, amountInWords,
                    warnings, adjustmentReason, agreementFileKey, xmlFileKey, pdfFileKey, rejectionCode,
                    rejectionMessage, submittedAt, submittedBy, issuedAt, voidedAt, voidedBy, voidReason, version,
                    correctionBasis, agreementNumber, agreementDate, returnRequestId, listedInvoiceIds,
                    deliveryFailedAt, null, null, null, null, null, null);
        }

        /** An invoice that corrects nothing, as every snapshot was before corrections existed. */
        public Snapshot(UUID id, String code, EInvoiceType type, EInvoiceStatus status, boolean adjusted,
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
            this(id, code, type, status, adjusted, orderId, orderNumber, contractNumber, shipmentId, shipmentCode,
                    deliveryBatchId, customerId, originalEInvoiceId, provider, attempt, transactionId, templateCode,
                    symbol, invoiceNumber, taxAuthorityCode, lookupCode, issueDate, issueDueAt, dueWarnedAt, signedAt,
                    seller, buyer, currency, paymentMethod, lines, subtotal, taxTotal, grandTotal, amountInWords,
                    warnings, adjustmentReason, agreementFileKey, xmlFileKey, pdfFileKey, rejectionCode,
                    rejectionMessage, submittedAt, submittedBy, issuedAt, voidedAt, voidedBy, voidReason, version,
                    null, null, null, null, List.of(), null);
        }
    }

    /**
     * What already corrects an invoice, read under its lock when another correction is drawn up.
     *
     * @param liveAdjustment      an adjustment of it (or one listing it) that is not voided
     * @param liveReplacement     a replacement of it that is not voided
     * @param liveAdjustmentTotal the totals with tax of the adjustments naming it that are not voided, added up
     */
    public record Corrections(boolean liveAdjustment, boolean liveReplacement, BigDecimal liveAdjustmentTotal) {

        public static final Corrections NONE = new Corrections(false, false, BigDecimal.ZERO);
    }

    /**
     * The delivery an original invoices, as the order module names it. For the invoice of an order's shipping fee rest
     * ({@link #drawUpOriginal}) the shipment, its code and its batch are null and {@code deliveredOn} is the day the
     * order's undelivered rest was closed.
     */
    public record Delivery(UUID orderId, String orderNumber, String contractNumber, UUID shipmentId,
                           String shipmentCode, UUID deliveryBatchId, UUID customerId, LocalDate deliveredOn) {
    }

    private final UUID id;
    private final String code;
    private final EInvoiceType type;
    private EInvoiceStatus status;
    /** Set when an adjustment of it is issued; the invoice stays in force. */
    private boolean adjusted;
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
    /** Re-dated at submit ({@link #dateForIssue}); otherwise as drawn up. */
    private LocalDate issueDate;
    private Instant issueDueAt;
    private final LocalDate deliveredOn;
    private final Instant reversedAt;
    private IssuedVia issuedVia;
    private UUID portalRecordedBy;
    private Instant portalRecordedAt;
    private VoidReasonCode voidReasonCode;
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
    private String agreementFileKey;
    private String agreementNumber;
    private LocalDate agreementDate;
    private final CorrectionBasis correctionBasis;
    private final UUID returnRequestId;
    private final List<UUID> listedInvoiceIds;
    /** Read only: see {@link Snapshot}. */
    private final Instant deliveryFailedAt;
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
        this.agreementNumber = s.agreementNumber();
        this.agreementDate = s.agreementDate();
        this.correctionBasis = s.correctionBasis();
        this.returnRequestId = s.returnRequestId();
        this.listedInvoiceIds = s.listedInvoiceIds();
        this.deliveryFailedAt = s.deliveryFailedAt();
        this.deliveredOn = s.deliveredOn();
        this.reversedAt = s.reversedAt();
        this.issuedVia = s.issuedVia();
        this.portalRecordedBy = s.portalRecordedBy();
        this.portalRecordedAt = s.portalRecordedAt();
        this.voidReasonCode = s.voidReasonCode();
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
     * As {@link #drawUpOriginal(UUID, String, Delivery, InvoiceParty, InvoiceParty, List, boolean, boolean, boolean)}
     * for lines whose tax classes are all known.
     */
    public static EInvoice drawUpOriginal(UUID id, String code, Delivery delivery, InvoiceParty seller,
                                          InvoiceParty buyer, List<EInvoiceLine> lines,
                                          boolean taxRateDiffersFromContract, boolean taxRateNotOnContract) {
        return drawUpOriginal(id, code, delivery, seller, buyer, lines, taxRateDiffersFromContract,
                taxRateNotOnContract, false);
    }

    /**
     * Draws up the original invoice of a delivered shipment, as a draft: dated the day of the delivery (NĐ 254 Điều 9
     * khoản 1 - the day the goods changed hands), which it keeps as its {@link #deliveredOn} for good, due by the end
     * of that day ({@link IssueDeadline}; WF-05 Q-1: no next-working-day grace for a system that issues
     * automatically), its totals added up from {@code lines}.
     *
     * <p>An original names no shipment in one case only: the rest of the order's fees once its deliveries are over -
     * the shipping fee no delivery carried ({@link FeeAllocation#shippingRest}) and a plate fee none did (WF-05 BE-62:
     * an order whose goods all came back). It carries fees and nothing else - goods are always a shipment's - and
     * {@code ck_einvoice_original} with {@code tg_einvoice_shipping_fee_rest_lines} say so in the database.</p>
     *
     * @param taxRateDiffersFromContract whether a line carries a VAT rate other than the one its order line fixed
     * @param taxRateNotOnContract       whether a line's order fixed no VAT rate at all
     * @param taxClassUnknown            whether a line's product has no tax class, so it was taxed at the highest rate
     *                                   ({@link Warning#TAX_CLASS_UNKNOWN})
     * @throws IllegalArgumentException for a seller missing its name, tax code or address, no lines, two lines with one
     *                                  number, a line below zero, or a line other than the shipping fee on an
     *                                  original that names no shipment - none of which a delivery produces
     */
    public static EInvoice drawUpOriginal(UUID id, String code, Delivery delivery, InvoiceParty seller,
                                          InvoiceParty buyer, List<EInvoiceLine> lines,
                                          boolean taxRateDiffersFromContract, boolean taxRateNotOnContract,
                                          boolean taxClassUnknown) {
        Objects.requireNonNull(delivery, "delivery");
        if (delivery.shipmentId() == null && lines != null
                && lines.stream().anyMatch(line -> line.kind() != EInvoiceLine.Kind.FEE)) {
            // The rest of an order's fees (BE-62): the shipping fee and plate fees no delivery's invoice carried.
            throw new IllegalArgumentException(
                    "An original invoices a shipment; one without a shipment carries the order's fees only");
        }
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
        buyerWarnings(buyer, warnings);
        if (taxRateDiffersFromContract) {
            warnings.add(Warning.TAX_RATE_DIFFERS_FROM_CONTRACT);
        }
        if (taxRateNotOnContract) {
            warnings.add(Warning.TAX_RATE_NOT_ON_CONTRACT);
        }
        if (taxClassUnknown) {
            warnings.add(Warning.TAX_CLASS_UNKNOWN);
        }
        if (atZeroRate(lines)) {
            warnings.add(Warning.TAX_EXEMPT_NOT_SUPPORTED);
        }
        return new EInvoice(new Snapshot(id, code, EInvoiceType.ORIGINAL, EInvoiceStatus.DRAFT, false,
                delivery.orderId(), delivery.orderNumber(), delivery.contractNumber(), delivery.shipmentId(),
                delivery.shipmentCode(), delivery.deliveryBatchId(), delivery.customerId(), null, null, 0, null, null,
                null, null, null, null, delivery.deliveredOn(),
                IssueDeadline.of(delivery.deliveredOn()), null, null, seller, buyer, VND,
                BANK_TRANSFER, lines, net, tax, total, VietnameseAmountInWords.of(total), warnings, null, null, null,
                null, null, null, null, null, null, null, null, null, 0, null, null, null, null, List.of(), null,
                delivery.deliveredOn(), null, null, null, null, null));
    }

    /**
     * As {@link #drawUpDeliveryReversal(UUID, String, EInvoice, String, LocalDate, Corrections, List)} for an invoice
     * no adjustment was issued against.
     */
    public static EInvoice drawUpDeliveryReversal(UUID id, String code, EInvoice original, String reason,
                                                  LocalDate issueDate, Corrections corrections) {
        return drawUpDeliveryReversal(id, code, original, reason, issueDate, corrections, List.of());
    }

    /**
     * Draws up the reduction of what an issued original still invoices, whose delivery was corrected to a failure, as
     * a draft ({@link CorrectionBasis#DELIVERY_FAILED}; kltn-docs 21 §4.4, 09 §4.3 step 5; WF-05 HĐ-3, BE-11): the
     * original's lines and the lines of every adjustment issued against it, added up line by line (the same goods or
     * fee at the same price, rate and unit), and what each still comes to taken off - so that the original, its issued
     * adjustments and this together come to nothing; each line's tax is rounded the same way below zero as above it
     * ({@link EInvoiceLine#vat}).
     *
     * <p>BE-11: an adjustment issued before the failure used to stop the system from reducing at all, and nothing
     * reduced the invoice afterwards: the goods were never delivered, yet the invoice stayed in force, and the
     * shipment delivered again rode on it uninvoiced. Reducing what is left, rather than the original as it was, is
     * what lets an adjusted invoice be reduced to nothing all the same. Draft corrections are not counted here: the
     * caller voids them first (nothing was delivered for them to correct), and refuses while one is at the provider.</p>
     *
     * <p>Never a replacement (WF-05 Q-9; TT 91 Điều 10 khoản 6 điểm a: a replacement is put right by replacement; SePay
     * refuses any adjustment of one, sandbox T-5): its failed delivery is the accountant's to settle on the provider's
     * portal and record ({@code EINVOICE_REPLACEMENT_NOT_ADJUSTABLE}).</p>
     *
     * @param original              read under its lock
     * @param corrections           what corrects it now: no replacement, and no adjustment but issued ones
     * @param issuedAdjustmentLines the lines of every adjustment issued against it (empty when it has none)
     * @throws BusinessException {@code EINVOICE_NOT_IN_FORCE} unless it is issued and not reversed;
     *         {@code EINVOICE_REPLACEMENT_NOT_ADJUSTABLE} for a replacement; {@code EINVOICE_CORRECTION_NOT_APPLICABLE}
     *         for an adjustment, an invoice of no shipment, one still being corrected, one with nothing left to take
     *         off, or one an adjustment lowered below its own line (a price taken down: reduced by hand)
     */
    public static EInvoice drawUpDeliveryReversal(UUID id, String code, EInvoice original, String reason,
                                                  LocalDate issueDate, Corrections corrections,
                                                  List<EInvoiceLine> issuedAdjustmentLines) {
        requireCorrectable(original);
        if (original.type == EInvoiceType.REPLACEMENT) {
            throw new BusinessException(ErrorCode.EINVOICE_REPLACEMENT_NOT_ADJUSTABLE, ("Invoice %s is a replacement: "
                    + "a replacement is not adjusted - its failed delivery is settled on the provider's portal and "
                    + "recorded").formatted(original.code));
        }
        List<EInvoiceLine> adjustments = issuedAdjustmentLines == null ? List.of() : issuedAdjustmentLines;
        if (original.shipmentId == null || corrections.liveReplacement()
                || (adjustments.isEmpty() && (original.adjusted || corrections.liveAdjustment()))) {
            throw new BusinessException(ErrorCode.EINVOICE_CORRECTION_NOT_APPLICABLE, ("Invoice %s is not an "
                    + "invoice of a delivery that only issued adjustments correct: it is reduced once they are settled")
                    .formatted(original.code));
        }
        List<EInvoiceLine> lines = whatIsLeft(original, adjustments);
        if (lines.isEmpty()) {
            throw new BusinessException(ErrorCode.EINVOICE_CORRECTION_NOT_APPLICABLE,
                    "Invoice %s invoices nothing any more: there is nothing to reduce".formatted(original.code));
        }
        return drawUpAdjustment(id, code, original, CorrectionBasis.DELIVERY_FAILED, reason, original.buyer, lines,
                issueDate, null, List.of(), corrections);
    }

    /**
     * What an issued invoice still invoices, as the lines that take it off: its own lines and the lines of the
     * adjustments issued against it added up line by line - the same goods or fee at the same price, rate and unit -
     * each that still comes to something taken off whole (WF-05 BE-11; also what a reduction made on the provider's
     * portal for a failed delivery is recorded as, Q-8).
     *
     * <p>Goods returned in loose pieces from a carton line were taken off in pieces, under a unit of their own, which
     * line by line would leave the pieces below zero and the cartons whole. Such goods are added up in pieces instead,
     * at the piece's price, and taken off as cartons when what is left is whole cartons, else as pieces.</p>
     *
     * @throws BusinessException {@code EINVOICE_CORRECTION_NOT_APPLICABLE} when an adjustment took a line below what the
     *         invoice invoiced in a way pieces do not explain (a price taken down, a discount): no reduction made of
     *         whole lines matches it
     */
    public static List<EInvoiceLine> whatIsLeft(EInvoice original, List<EInvoiceLine> issuedAdjustmentLines) {
        List<EInvoiceLine> adjustments = issuedAdjustmentLines == null ? List.of() : issuedAdjustmentLines;
        // The same goods or fee at the same rate, then the same unit and price within it.
        java.util.Map<List<Object>, java.util.Map<List<Object>, List<EInvoiceLine>>> groups =
                new java.util.LinkedHashMap<>();
        for (List<EInvoiceLine> source : List.of(original.lines, adjustments)) {
            for (EInvoiceLine line : source) {
                if (line.kind() == EInvoiceLine.Kind.NOTE) {
                    continue;
                }
                List<Object> group = java.util.Arrays.asList(line.kind(), line.feeKind(), line.orderLineId(),
                        line.sku(), line.taxRate().stripTrailingZeros());
                List<Object> key = java.util.Arrays.asList(line.unit(), line.unitsPerCarton(),
                        line.unitPrice().stripTrailingZeros());
                groups.computeIfAbsent(group, g -> new java.util.LinkedHashMap<>())
                        .computeIfAbsent(key, k -> new ArrayList<>()).add(line);
            }
        }
        List<EInvoiceLine> lines = new ArrayList<>();
        for (java.util.Map<List<Object>, List<EInvoiceLine>> group : groups.values()) {
            boolean belowZero = group.values().stream()
                    .anyMatch(same -> same.stream().mapToLong(EInvoiceLine::quantity).sum() < 0);
            if (!belowZero) {
                for (List<EInvoiceLine> same : group.values()) {
                    long quantity = same.stream().mapToLong(EInvoiceLine::quantity).sum();
                    if (quantity > 0) {
                        EInvoiceLine line = same.getFirst();
                        lines.add(line.corrected(Identifiers.newId(), lines.size() + 1, (int) -quantity,
                                line.unitsPerCarton(), line.unit(), line.unitPrice(), line.taxRate(), line.itemName()));
                    }
                }
                continue;
            }
            List<EInvoiceLine> left = inPieces(group.values().stream().flatMap(List::stream).toList(),
                    lines.size() + 1);
            if (left == null) {
                throw new BusinessException(ErrorCode.EINVOICE_CORRECTION_NOT_APPLICABLE, ("Invoice %s: an adjustment "
                        + "took a line below what it invoiced; its failed delivery is reduced on the provider's portal "
                        + "and recorded").formatted(original.code));
            }
            lines.addAll(left);
        }
        return lines;
    }

    /**
     * The lines of one goods line added up in pieces, as the line that takes what is left off: one line, or none when
     * nothing is left (a line of quantity 0 is no line, {@link EInvoiceLine}); null when they do not add up in pieces
     * (a fee, a price not the same per piece, below zero, or a rest of loose pieces with no piece line to take its unit
     * from).
     */
    private static List<EInvoiceLine> inPieces(List<EInvoiceLine> same, int lineNo) {
        if (same.getFirst().kind() != EInvoiceLine.Kind.GOODS) {
            return null;
        }
        BigDecimal piecePrice = null;
        EInvoiceLine carton = null;
        EInvoiceLine piece = null;
        long pieces = 0;
        for (EInvoiceLine line : same) {
            int perCarton = line.unitsPerCarton() == null ? 1 : line.unitsPerCarton();
            BigDecimal[] price = line.unitPrice().divideAndRemainder(BigDecimal.valueOf(perCarton));
            if (price[1].signum() != 0 || piecePrice != null && piecePrice.compareTo(price[0]) != 0) {
                return null;
            }
            piecePrice = price[0];
            pieces += line.pieces();
            if (line.unitsPerCarton() != null && carton == null) {
                carton = line;
            } else if (line.unitsPerCarton() == null && piece == null) {
                piece = line;
            }
        }
        if (pieces < 0) {
            return null;
        }
        if (pieces == 0) {
            return List.of();
        }
        if (carton != null && pieces % carton.unitsPerCarton() == 0) {
            return List.of(carton.corrected(Identifiers.newId(), lineNo, (int) -(pieces / carton.unitsPerCarton()),
                    carton.unitsPerCarton(), carton.unit(), carton.unitPrice(), carton.taxRate(), carton.itemName()));
        }
        if (piece == null) {
            return null;
        }
        return List.of(piece.corrected(Identifiers.newId(), lineNo, (int) -pieces, null, piece.unit(), piecePrice,
                piece.taxRate(), piece.itemName()));
    }

    /**
     * Draws up an adjustment of an issued invoice, as a draft (TT 91 Điều 10): what changes, line by line, signed.
     *
     * @param original        the invoice adjusted, read under its lock
     * @param buyer           the buyer as the adjustment names it: the original's, or with the tax code put right
     * @param lines           what changes; below zero reduces. Only reductions for a return or a trade discount
     * @param issueDate       the day the adjustment is drawn up
     * @param returnRequestId the return it is for; with {@link CorrectionBasis#RETURN} and only then
     * @param listed          the other invoices in force of the buyer a trade discount covers; empty otherwise
     * @param corrections     what already corrects {@code original}
     * @throws BusinessException {@code EINVOICE_NOT_IN_FORCE} unless the original (and every listed invoice) is issued
     *         and in force; {@code EINVOICE_CORRECTION_NOT_APPLICABLE} for an adjustment of an adjustment, a line that
     *         adds on a return or discount, or a list that is not the buyer's other invoices;
     *         {@code EINVOICE_CORRECTION_FORM_FIXED} when the invoice is being replaced, or for the correction of an
     *         error on a replacement (which is replaced again); {@code EINVOICE_REPLACEMENT_NOT_ADJUSTABLE} for a
     *         return, a trade discount or a failed delivery on a replacement, or a replacement listed by a trade
     *         discount (WF-05 Q-9: a replacement is never adjusted - TT 91 Điều 10 khoản 6 điểm a; SePay refuses it,
     *         sandbox T-5 - and a return on it is settled on the provider's portal and recorded);
     *         {@code EINVOICE_ADJUSTMENT_EXCEEDS_INVOICE} when it would reduce what it covers below nothing;
     *         {@code VALIDATION_FAILED} without a reason or lines
     */
    public static EInvoice drawUpAdjustment(UUID id, String code, EInvoice original, CorrectionBasis basis,
                                            String reason, InvoiceParty buyer, List<EInvoiceLine> lines,
                                            LocalDate issueDate, UUID returnRequestId, List<EInvoice> listed,
                                            Corrections corrections) {
        Objects.requireNonNull(basis, "basis");
        requireCorrectable(original);
        if (corrections.liveReplacement()
                || (basis == CorrectionBasis.ERROR && original.type == EInvoiceType.REPLACEMENT)) {
            throw new BusinessException(ErrorCode.EINVOICE_CORRECTION_FORM_FIXED,
                    "Invoice %s has its errors corrected by replacement; it is replaced again, not adjusted"
                            .formatted(original.code));
        }
        if (original.type == EInvoiceType.REPLACEMENT) {
            throw new BusinessException(ErrorCode.EINVOICE_REPLACEMENT_NOT_ADJUSTABLE, ("Invoice %s is a replacement and "
                    + "is never adjusted: a return or a reduction on it is made on the provider's portal and recorded")
                    .formatted(original.code));
        }
        if ((basis == CorrectionBasis.RETURN) != (returnRequestId != null)) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "returnRequestId: an adjustment for returned goods names the return, and no other does");
        }
        List<EInvoice> others = listed == null ? List.of() : listed;
        if (!others.isEmpty() && basis != CorrectionBasis.TRADE_DISCOUNT) {
            throw new BusinessException(ErrorCode.EINVOICE_CORRECTION_NOT_APPLICABLE,
                    "Only a trade-discount adjustment lists other invoices");
        }
        BigDecimal covered = original.grandTotal;
        Set<UUID> seen = new HashSet<>();
        seen.add(original.id);
        for (EInvoice other : others) {
            if (other.status != EInvoiceStatus.ISSUED) {
                throw new BusinessException(ErrorCode.EINVOICE_NOT_IN_FORCE,
                        "Invoice %s is %s: only an invoice in force is listed".formatted(other.code, other.status));
            }
            if (other.type == EInvoiceType.ADJUSTMENT || !other.customerId.equals(original.customerId)
                    || !seen.add(other.id)) {
                throw new BusinessException(ErrorCode.EINVOICE_CORRECTION_NOT_APPLICABLE,
                        "Invoice %s is not another invoice of the same buyer".formatted(other.code));
            }
            if (other.type == EInvoiceType.REPLACEMENT) {
                throw new BusinessException(ErrorCode.EINVOICE_REPLACEMENT_NOT_ADJUSTABLE,
                        "Invoice %s is a replacement: a trade discount does not adjust it".formatted(other.code));
            }
            if (other.reversedAt != null) {
                throw new BusinessException(ErrorCode.EINVOICE_NOT_IN_FORCE, ("Invoice %s was reduced in full for a "
                        + "failed delivery").formatted(other.code));
            }
            covered = covered.add(other.grandTotal);
        }
        Totals totals = totals(lines, reason);
        for (EInvoiceLine line : lines) {
            if (basis.decreaseOnly() && (line.quantity() > 0 || line.kind() == EInvoiceLine.Kind.NOTE)) {
                throw new BusinessException(ErrorCode.EINVOICE_CORRECTION_NOT_APPLICABLE,
                        "An adjustment for a return or a trade discount only reduces (line %d)".formatted(line.lineNo()));
            }
        }
        if (covered.add(corrections.liveAdjustmentTotal()).add(totals.total()).signum() < 0) {
            throw new BusinessException(ErrorCode.EINVOICE_ADJUSTMENT_EXCEEDS_INVOICE,
                    "Adjusting invoice %s by %s would reduce it below nothing: it holds %s and is already adjusted by %s"
                            .formatted(original.code, totals.total().toPlainString(), covered.toPlainString(),
                                    corrections.liveAdjustmentTotal().toPlainString()));
        }
        return correction(id, code, EInvoiceType.ADJUSTMENT, original, basis, reason, buyer, lines, totals, issueDate,
                returnRequestId, others.stream().map(EInvoice::id).toList());
    }

    /**
     * Draws up the replacement of an issued invoice, as a draft (TT 91 Điều 10 khoản 1 điểm b.2): the invoice as it
     * should have been. The original becomes REPLACED when this is issued, not before - until then it is the invoice in
     * force.
     *
     * @throws BusinessException {@code EINVOICE_NOT_IN_FORCE}; {@code EINVOICE_CORRECTION_NOT_APPLICABLE} for a
     *         replacement of an adjustment or a line below zero; {@code EINVOICE_CORRECTION_FORM_FIXED} when the
     *         invoice has an adjustment or is already being replaced; {@code VALIDATION_FAILED} without a reason or lines
     */
    public static EInvoice drawUpReplacement(UUID id, String code, EInvoice original, String reason,
                                             InvoiceParty buyer, List<EInvoiceLine> lines, LocalDate issueDate,
                                             Corrections corrections) {
        requireCorrectable(original);
        if (original.adjusted || corrections.liveAdjustment() || corrections.liveReplacement()) {
            throw new BusinessException(ErrorCode.EINVOICE_CORRECTION_FORM_FIXED, corrections.liveReplacement()
                    ? "Invoice %s is already being replaced".formatted(original.code)
                    : "Invoice %s has an adjustment; it is adjusted again, not replaced".formatted(original.code));
        }
        Totals totals = totals(lines, reason);
        for (EInvoiceLine line : lines) {
            if (line.quantity() < 0 || line.kind() == EInvoiceLine.Kind.NOTE) {
                throw new BusinessException(ErrorCode.EINVOICE_CORRECTION_NOT_APPLICABLE,
                        "A replacement states the invoice as it should be: no line below zero (line %d)"
                                .formatted(line.lineNo()));
            }
        }
        return correction(id, code, EInvoiceType.REPLACEMENT, original, CorrectionBasis.ERROR, reason, buyer, lines,
                totals, issueDate, null, List.of());
    }

    private record Totals(BigDecimal net, BigDecimal tax, BigDecimal total) {
    }

    private static void requireCorrectable(EInvoice original) {
        Objects.requireNonNull(original, "original");
        if (original.status != EInvoiceStatus.ISSUED) {
            throw new BusinessException(ErrorCode.EINVOICE_NOT_IN_FORCE,
                    "Invoice %s is %s: only an issued invoice in force is corrected".formatted(original.code,
                            original.status));
        }
        if (original.type == EInvoiceType.ADJUSTMENT) {
            throw new BusinessException(ErrorCode.EINVOICE_CORRECTION_NOT_APPLICABLE,
                    "Invoice %s is an adjustment: correct the invoice it adjusts".formatted(original.code));
        }
        // BE-36: reduced in full for a failed delivery, it invoices nothing any more - an adjustment up or a
        // replacement of it would invoice goods nobody received. tg_einvoice_original_in_force says the same.
        if (original.reversedAt != null) {
            throw new BusinessException(ErrorCode.EINVOICE_NOT_IN_FORCE, ("Invoice %s was reduced in full because its "
                    + "delivery failed; it is not corrected again").formatted(original.code));
        }
    }

    private static Totals totals(List<EInvoiceLine> lines, String reason) {
        if (reason == null || reason.isBlank() || reason.strip().length() > 500) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "reason: what is corrected, in at most 500 characters");
        }
        if (lines == null || lines.isEmpty()) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "lines: a correction has at least one line");
        }
        Set<Integer> numbers = new HashSet<>();
        BigDecimal net = BigDecimal.ZERO;
        BigDecimal tax = BigDecimal.ZERO;
        for (EInvoiceLine line : lines) {
            if (!numbers.add(line.lineNo())) {
                throw new IllegalArgumentException("Two lines are numbered " + line.lineNo());
            }
            net = net.add(line.amount());
            tax = tax.add(line.taxAmount());
        }
        return new Totals(net, tax, net.add(tax));
    }

    private static EInvoice correction(UUID id, String code, EInvoiceType type, EInvoice original,
                                       CorrectionBasis basis, String reason, InvoiceParty buyer,
                                       List<EInvoiceLine> lines, Totals totals, LocalDate issueDate,
                                       UUID returnRequestId, List<UUID> listed) {
        Objects.requireNonNull(issueDate, "issueDate");
        if (buyer == null || buyer.name() == null) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "buyer.name: required");
        }
        Set<Warning> warnings = EnumSet.noneOf(Warning.class);
        buyerWarnings(buyer, warnings);
        if (atZeroRate(lines)) {
            warnings.add(Warning.TAX_EXEMPT_NOT_SUPPORTED);
        }
        return new EInvoice(new Snapshot(id, code, type, EInvoiceStatus.DRAFT, false, original.orderId,
                original.orderNumber, original.contractNumber, original.shipmentId, original.shipmentCode,
                original.deliveryBatchId, original.customerId, original.id, null, 0, null, null, null, null, null, null,
                issueDate, IssueDeadline.of(issueDate), null, null, original.seller, buyer, VND, BANK_TRANSFER, lines,
                totals.net(), totals.tax(), totals.total(), VietnameseAmountInWords.of(totals.total()), warnings,
                reason.strip(), null, null, null, null, null, null, null, null, null, null, null, 0, basis, null, null,
                returnRequestId, listed, null, null, null, null, null, null, null));
    }

    /**
     * A reduction the accountant made on the provider's portal, about to be recorded (WF-05 Q-8; BE-10, Q-9): an
     * adjustment like any other, marked {@link IssuedVia#PORTAL_RECORD} from the start - it is never sent anywhere,
     * and the caller takes it to issued in the same transaction ({@link #submit} then {@link #issuedFromPortal}).
     * Unlike {@link #drawUpAdjustment} it is taken for an invoice already reduced on paper here - an original whose
     * own reduction no provider could send ({@link VoidReasonCode#UNSENDABLE_REVERSAL}), or a replacement, which no
     * provider adjusts - and dated the day the portal gave it.
     *
     * @param basis {@link CorrectionBasis#DELIVERY_FAILED} or {@link CorrectionBasis#RETURN}
     * @throws BusinessException {@code EINVOICE_NOT_IN_FORCE} unless the invoice is issued; {@code
     *         EINVOICE_CORRECTION_NOT_APPLICABLE} for an adjustment, another basis, or a line that adds;
     *         {@code EINVOICE_ADJUSTMENT_EXCEEDS_INVOICE} below nothing
     */
    public static EInvoice recordedReduction(UUID id, String code, EInvoice original, CorrectionBasis basis,
                                             String reason, List<EInvoiceLine> lines, LocalDate issueDate,
                                             UUID returnRequestId, Corrections corrections) {
        Objects.requireNonNull(original, "original");
        if (original.status != EInvoiceStatus.ISSUED || original.type == EInvoiceType.ADJUSTMENT) {
            throw new BusinessException(ErrorCode.EINVOICE_NOT_IN_FORCE,
                    "Invoice %s is %s: only an issued invoice is reduced".formatted(original.code, original.status));
        }
        if (basis != CorrectionBasis.DELIVERY_FAILED && basis != CorrectionBasis.RETURN
                || (basis == CorrectionBasis.RETURN) != (returnRequestId != null)) {
            throw new BusinessException(ErrorCode.EINVOICE_CORRECTION_NOT_APPLICABLE,
                    "A reduction recorded from the portal is for a failed delivery, or for a return it names");
        }
        Totals totals = totals(lines, reason);
        for (EInvoiceLine line : lines) {
            if (line.quantity() > 0 || line.kind() == EInvoiceLine.Kind.NOTE) {
                throw new BusinessException(ErrorCode.EINVOICE_CORRECTION_NOT_APPLICABLE,
                        "A reduction only takes off (line %d)".formatted(line.lineNo()));
            }
        }
        if (original.grandTotal.add(corrections.liveAdjustmentTotal()).add(totals.total()).signum() < 0) {
            throw new BusinessException(ErrorCode.EINVOICE_ADJUSTMENT_EXCEEDS_INVOICE,
                    "Reducing invoice %s by %s would take it below nothing".formatted(original.code,
                            totals.total().toPlainString()));
        }
        EInvoice recorded = correction(id, code, EInvoiceType.ADJUSTMENT, original, basis, reason, original.buyer,
                lines, totals, issueDate, returnRequestId, List.of());
        recorded.issuedVia = IssuedVia.PORTAL_RECORD;
        return recorded;
    }

    /**
     * Audit r3 EINV-2, the ERROR half (Q-8: "Cùng một lệnh này dùng cho mọi ca SePay không làm được qua API"; HĐ-5): the
     * correction of an error that reduces an original, drawn up here and agreed in writing with the buyer, that no
     * provider could send - the accountant voided the draft and made it on the provider's portal - about to be recorded
     * as {@link IssuedVia#PORTAL_RECORD}. It states what the voided draft stated, line for line, and carries its
     * agreement: that is what both parties signed (TT 91/2026/TT-BTC Điều 10 khoản 1 điểm b), so nothing in it is
     * recomputed or trimmed. Only a reduction is recorded this way - every line takes off, and the whole is below zero;
     * a correction that raises or only rewords is not a reduction made on the portal.
     *
     * @param voided the voided ERROR adjustment of {@code original}, its agreement attached
     * @throws BusinessException {@code EINVOICE_NOT_IN_FORCE} unless {@code original} is an issued invoice that is not
     *         an adjustment; {@code EINVOICE_CORRECTION_NOT_APPLICABLE} for anything but a voided ERROR adjustment of
     *         it, one with no agreement, or one with a line that adds or only words; {@code
     *         EINVOICE_ADJUSTMENT_EXCEEDS_INVOICE} below nothing
     */
    public static EInvoice recordedErrorReduction(UUID id, String code, EInvoice original, EInvoice voided,
                                                  String reason, LocalDate issueDate, Corrections corrections) {
        Objects.requireNonNull(original, "original");
        Objects.requireNonNull(voided, "voided");
        if (original.status != EInvoiceStatus.ISSUED || original.type == EInvoiceType.ADJUSTMENT) {
            throw new BusinessException(ErrorCode.EINVOICE_NOT_IN_FORCE,
                    "Invoice %s is %s: only an issued invoice is reduced".formatted(original.code, original.status));
        }
        if (voided.type != EInvoiceType.ADJUSTMENT || voided.correctionBasis != CorrectionBasis.ERROR
                || voided.status != EInvoiceStatus.VOIDED_DRAFT || !original.id.equals(voided.originalEInvoiceId)) {
            throw new BusinessException(ErrorCode.EINVOICE_CORRECTION_NOT_APPLICABLE, ("Invoice %s is not a voided "
                    + "correction of an error of %s").formatted(voided.code, original.code));
        }
        if (voided.agreementFileKey == null) {
            throw new BusinessException(ErrorCode.EINVOICE_CORRECTION_NOT_APPLICABLE, ("Invoice %s carries no written "
                    + "agreement with the buyer: a correction of an error is not made without one").formatted(voided.code));
        }
        if (!reducesOnly(voided.lines)) {
            throw new BusinessException(ErrorCode.EINVOICE_CORRECTION_NOT_APPLICABLE, ("Invoice %s does not only take "
                    + "off: only a reduction made on the provider's portal is recorded").formatted(voided.code));
        }
        List<EInvoiceLine> lines = new ArrayList<>();
        for (EInvoiceLine line : voided.lines) {
            lines.add(line.corrected(Identifiers.newId(), lines.size() + 1, line.quantity(), line.unitsPerCarton(),
                    line.unit(), line.unitPrice(), line.taxRate(), line.itemName()));
        }
        Totals totals = totals(lines, reason);
        if (original.grandTotal.add(corrections.liveAdjustmentTotal()).add(totals.total()).signum() < 0) {
            throw new BusinessException(ErrorCode.EINVOICE_ADJUSTMENT_EXCEEDS_INVOICE,
                    "Reducing invoice %s by %s would take it below nothing".formatted(original.code,
                            totals.total().toPlainString()));
        }
        EInvoice recorded = correction(id, code, EInvoiceType.ADJUSTMENT, original, CorrectionBasis.ERROR, reason,
                voided.buyer, lines, totals, issueDate, null, List.of());
        recorded.agreementFileKey = voided.agreementFileKey;
        recorded.agreementNumber = voided.agreementNumber;
        recorded.agreementDate = voided.agreementDate;
        recorded.issuedVia = IssuedVia.PORTAL_RECORD;
        return recorded;
    }

    /**
     * Whether the lines of a correction only take off: none is a note, none adds to the amount or the tax, and they
     * take something off in all - what {@link #recordedErrorReduction} records.
     */
    public static boolean reducesOnly(List<EInvoiceLine> lines) {
        BigDecimal total = BigDecimal.ZERO;
        for (EInvoiceLine line : lines) {
            if (line.kind() == EInvoiceLine.Kind.NOTE || line.amount().signum() > 0 || line.taxAmount().signum() > 0) {
                return false;
            }
            total = total.add(line.amount()).add(line.taxAmount());
        }
        return total.signum() < 0;
    }

    /** {@link Warning#BUYER_PROFILE_INCOMPLETE} and {@link Warning#BUYER_EMAIL_MISSING}, as the buyer is now. */
    private static void buyerWarnings(InvoiceParty buyer, Set<Warning> warnings) {
        warnings.remove(Warning.BUYER_PROFILE_INCOMPLETE);
        warnings.remove(Warning.BUYER_EMAIL_MISSING);
        if (!buyer.completeBuyer()) {
            warnings.add(Warning.BUYER_PROFILE_INCOMPLETE);
        }
        if (buyer.email() == null) {
            warnings.add(Warning.BUYER_EMAIL_MISSING);
        }
    }

    /** Whether a line other than a note is at 0%: only an EXEMPT one ({@link Warning#TAX_EXEMPT_NOT_SUPPORTED}). */
    private static boolean atZeroRate(List<EInvoiceLine> lines) {
        return lines.stream().anyMatch(line -> line.kind() != EInvoiceLine.Kind.NOTE && line.taxRate().signum() == 0);
    }

    /** An invoice as it was stored. */
    public static EInvoice restore(Snapshot snapshot) {
        return new EInvoice(snapshot);
    }

    // ---- corrections on a draft ---------------------------------------------------------------------------------

    /**
     * The warnings of a draft worked out again just before the automatic issuing sends it (WF-05 review, defect 4):
     * what drawing it up decides - the buyer's gaps, a line at 0% - as the draft stands now, and the rate facts the
     * caller found by comparing it with a draft drawn up now for the same delivery. Warnings are decided when a draft
     * is drawn up and the automatic issuing takes only drafts without any, so a draft drawn up before a rule existed
     * (the e-mail warning, the VAT reduction period) would be issued as if it never did. Only adds: a warning the
     * accountant has not dealt with stays.
     *
     * @return the warnings added now; empty when the draft may still be issued automatically
     * @throws BusinessException {@code EINVOICE_NOT_DRAFT}
     */
    public Set<Warning> recheckBeforeAutomaticIssue(boolean rateOutOfDate, boolean rateDiffersFromContract,
                                                    boolean rateNotOnContract) {
        return recheckBeforeIssue(rateOutOfDate, rateDiffersFromContract, rateNotOnContract, false);
    }

    /**
     * The warnings of a draft worked out again just before it is issued, automatically or by the accountant (WF-05
     * BE-25: a manual issue applies every check the automatic one does), with {@code taxClassUnknown}: a line's
     * product has no tax class now ({@link Warning#TAX_CLASS_UNKNOWN}). Only adds.
     *
     * @return the warnings added now
     * @throws BusinessException {@code EINVOICE_NOT_DRAFT}
     */
    public Set<Warning> recheckBeforeIssue(boolean rateOutOfDate, boolean rateDiffersFromContract,
                                           boolean rateNotOnContract, boolean taxClassUnknown) {
        requireDraft("check before issuing");
        Set<Warning> found = EnumSet.noneOf(Warning.class);
        if (!buyer.completeBuyer()) {
            found.add(Warning.BUYER_PROFILE_INCOMPLETE);
        }
        if (buyer.email() == null) {
            found.add(Warning.BUYER_EMAIL_MISSING);
        }
        if (atZeroRate(lines)) {
            found.add(Warning.TAX_EXEMPT_NOT_SUPPORTED);
        }
        if (rateOutOfDate) {
            found.add(Warning.TAX_RATE_OUT_OF_DATE);
        }
        if (rateDiffersFromContract) {
            found.add(Warning.TAX_RATE_DIFFERS_FROM_CONTRACT);
        }
        if (rateNotOnContract) {
            found.add(Warning.TAX_RATE_NOT_ON_CONTRACT);
        }
        if (taxClassUnknown) {
            found.add(Warning.TAX_CLASS_UNKNOWN);
        }
        found.removeAll(warnings);
        warnings.addAll(found);
        return found;
    }

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
        buyerWarnings(revised, warnings);
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

    /**
     * The written agreement of the two parties on what was wrong (TT 91 Điều 10 khoản 1 điểm b): its signed file, its
     * number and its date. On a draft correction of an error; attaching again replaces it. On the reduction of a
     * delivery corrected to a failure the same three fields carry its written evidence, which is accepted but not
     * required ({@link CorrectionBasis#DELIVERY_FAILED}).
     *
     * @param today no agreement is dated after the day it is attached
     * @throws BusinessException {@code EINVOICE_NOT_DRAFT}; {@code EINVOICE_CORRECTION_NOT_APPLICABLE} for an invoice
     *         that takes none (an original, a return, a trade discount); {@code VALIDATION_FAILED} for a blank or long
     *         number or a date in the future
     */
    public void attachAgreement(String fileKey, String number, LocalDate date, LocalDate today) {
        requireDraft("attach an agreement to");
        if (correctionBasis == null || !correctionBasis.acceptsAgreement()) {
            throw new BusinessException(ErrorCode.EINVOICE_CORRECTION_NOT_APPLICABLE,
                    "Invoice %s needs no agreement: only the correction of an error is agreed in writing".formatted(code));
        }
        if (number == null || number.isBlank() || number.strip().length() > 64 || date == null
                || date.isAfter(today)) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "agreement: its number (at most 64 characters) and its date, not after today");
        }
        this.agreementFileKey = requireText(fileKey, "fileKey");
        this.agreementNumber = number.strip();
        this.agreementDate = date;
    }

    /**
     * An adjustment of this invoice was issued: it stays in force and says so. Marking twice changes nothing.
     *
     * @throws BusinessException {@code EINVOICE_NOT_IN_FORCE} unless it is issued and in force
     */
    public void markAdjusted() {
        if (status != EInvoiceStatus.ISSUED) {
            throw new BusinessException(ErrorCode.EINVOICE_NOT_IN_FORCE,
                    "Invoice %s is %s: only an invoice in force is adjusted".formatted(code, status));
        }
        this.adjusted = true;
    }

    /**
     * Its replacement was issued: ISSUED → REPLACED, final. Never for an invoice with an adjustment - the form chosen
     * for an invoice is kept (khoản 6 điểm a).
     */
    public void replaced() {
        if (adjusted) {
            throw new BusinessException(ErrorCode.EINVOICE_CORRECTION_FORM_FIXED,
                    "Invoice %s has an adjustment; it is not replaced".formatted(code));
        }
        move(EInvoiceStatus.REPLACED);
    }

    // ---- lifecycle ----------------------------------------------------------------------------------------------

    /**
     * Dates the draft for the day it is issued, before it is submitted (WF-05 Q-1, BE-01), in the transaction that
     * submits it - so the series is chosen for that day's year, and nothing changes if the submission is refused.
     * <ul>
     *   <li><b>A correction</b> - an adjustment, a replacement, the reduction of a failed delivery - is dated the day
     *       it is issued, under every provider: it is signed that day, it was only drawn up before, and SePay refuses
     *       any other day for good (sandbox T-6). It used to keep the day it was drawn up, and a reduction that could
     *       not be voided was then refused for ever.</li>
     *   <li><b>An original</b> keeps its delivery day, unless the provider takes only today's date
     *       ({@code datesOnlyToday}, SePay, sandbox T-1): then it is dated today, and if that is after its delivery
     *       day it is flagged {@link Warning#ISSUED_LATE} - it was due on its delivery day, and the delivery day is
     *       never moved to match (Luật Kế toán Điều 13).</li>
     * </ul>
     * The deadline follows the date ({@link IssueDeadline#of(LocalDate)}). Never before the delivery day.
     *
     * @return whether it was flagged {@link Warning#ISSUED_LATE} now
     * @throws BusinessException {@code EINVOICE_INVALID_TRANSITION} unless it is a draft
     */
    public boolean dateForIssue(LocalDate today, boolean datesOnlyToday) {
        requireCanBecome(EInvoiceStatus.SUBMITTING);
        Objects.requireNonNull(today, "today");
        if (type == EInvoiceType.ORIGINAL && !datesOnlyToday) {
            return false;
        }
        LocalDate floor = deliveredOn != null ? deliveredOn : type == EInvoiceType.ORIGINAL ? issueDate : null;
        LocalDate day = floor != null && today.isBefore(floor) ? floor : today;
        if (!day.equals(issueDate)) {
            this.issueDate = day;
            this.issueDueAt = IssueDeadline.of(day);
        }
        return type == EInvoiceType.ORIGINAL && deliveredOn != null && deliveredOn.isBefore(issueDate)
                && warnings.add(Warning.ISSUED_LATE);
    }

    /** Whether, issued on {@code today} under a provider that takes only today's date, it would be late (Q-10). */
    public boolean lateIfIssuedOn(LocalDate today, boolean datesOnlyToday) {
        return type == EInvoiceType.ORIGINAL && datesOnlyToday && deliveredOn != null && deliveredOn.isBefore(today);
    }

    /**
     * Sends the draft on its way to the provider: DRAFT → SUBMITTING, under a new transaction id.
     *
     * @param by who issued it; null when it was issued automatically
     * @throws BusinessException {@code EINVOICE_INVALID_TRANSITION} unless it is a draft;
     *                           {@code EINVOICE_BUYER_PROFILE_INCOMPLETE} while the buyer's name, tax code or address
     *                           is missing (a missing e-mail is only {@link Warning#BUYER_EMAIL_MISSING});
     *                           {@code EINVOICE_AGREEMENT_REQUIRED} for the correction
     *                           of an error without the agreement of both parties;
     *                           {@code EINVOICE_TAX_EXEMPT_NOT_SUPPORTED} for a line at 0%
     *                           ({@link Warning#TAX_EXEMPT_NOT_SUPPORTED});
     *                           {@code EINVOICE_SERIES_NOT_CONFIGURED} for a series of another year than the invoice's
     *                           date, or of a template other than a VAT invoice's
     */
    public void submit(InvoiceSeries series, UUID by, Instant now) {
        requireCanBecome(EInvoiceStatus.SUBMITTING);
        if (!buyer.completeBuyer()) {
            throw new BusinessException(ErrorCode.EINVOICE_BUYER_PROFILE_INCOMPLETE,
                    "Invoice %s: the buyer's name, tax code and address are all needed".formatted(code));
        }
        if (correctionBasis != null && correctionBasis.requiresAgreement() && agreementFileKey == null) {
            throw new BusinessException(ErrorCode.EINVOICE_AGREEMENT_REQUIRED, ("Invoice %s corrects an error: the "
                    + "written agreement with the buyer - file, number and date - is needed before it is sent "
                    + "(TT 91/2026/TT-BTC Điều 10 khoản 1 điểm b)").formatted(code));
        }
        if (atZeroRate(lines)) {
            throw new BusinessException(ErrorCode.EINVOICE_TAX_EXEMPT_NOT_SUPPORTED, ("Invoice %s has a line at 0%%: "
                    + "the tax class EXEMPT (not subject to VAT) cannot be issued as 0%%, which is a rate of its own")
                    .formatted(code));
        }
        Objects.requireNonNull(series, "series");
        if (!series.vatTemplate()) {
            throw new BusinessException(ErrorCode.EINVOICE_SERIES_NOT_CONFIGURED,
                    "Series %s is of template %s; invoices are issued under %s, a VAT invoice".formatted(
                            series.symbol(), series.templateCode(), InvoiceSeries.VAT_TEMPLATE));
        }
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
     * A correction at a provider that chose its series itself ({@code EInvoiceProvider.picksCorrectionSeries}, WF-05
     * BE-30) is recorded under the symbol the provider numbered it under, just before it is recorded issued: the
     * symbol it was sent under was only the series in use here on its day. Never an original - it was sent under its
     * series, and one numbered elsewhere is not recorded at all. {@code tg_einvoice_guard} allows the change on this
     * one step only.
     *
     * @throws BusinessException {@code EINVOICE_INVALID_TRANSITION} unless it is a correction at the provider
     */
    public void numberedUnder(String providerSymbol) {
        if (status != EInvoiceStatus.SUBMITTING || type == EInvoiceType.ORIGINAL) {
            throw new BusinessException(ErrorCode.EINVOICE_INVALID_TRANSITION, ("Invoice %s is %s %s: only a correction "
                    + "at the provider takes the series the provider chose").formatted(code, type, status));
        }
        this.symbol = requireText(providerSymbol, "symbol");
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
        if (issuedVia == null) {
            this.issuedVia = IssuedVia.PROVIDER;
        }
        // Whatever was worth a look is settled by the invoice having been issued - but that it was issued late stays
        // for good, to be counted (Q-10).
        this.warnings.retainAll(EnumSet.of(Warning.ISSUED_LATE));
    }

    /**
     * Recorded as issued from the provider's portal by the accountant (WF-05 BE-08, Q-8): SUBMITTING → ISSUED, as
     * {@link #issued} with what the portal shows, {@link IssuedVia#PORTAL_RECORD} and who recorded it. The caller has
     * checked it against a fresh look-up and the invoice's own XML.
     */
    public void issuedFromPortal(int number, String taxAuthorityCode, String lookupCode, Instant signedAt,
                                 String xmlFileKey, String pdfFileKey, UUID by, Instant now) {
        this.issuedVia = IssuedVia.PORTAL_RECORD;
        issued(number, taxAuthorityCode, lookupCode, signedAt, xmlFileKey, pdfFileKey, now);
        this.portalRecordedBy = Objects.requireNonNull(by, "by");
        this.portalRecordedAt = now;
    }

    /**
     * The seller's signature on the XML just handed back does not hold ({@link Warning#SIGNATURE_INVALID}). Only while
     * the invoice is being recorded as issued: in the same write, since an issued invoice changes no more
     * ({@code tg_einvoice_issued_immutable}).
     */
    public void signatureInvalid() {
        if (status != EInvoiceStatus.ISSUED || issuedAt == null) {
            throw new IllegalStateException("Only an invoice being recorded as issued is flagged for its signature");
        }
        warnings.add(Warning.SIGNATURE_INVALID);
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
     * never for an issued invoice - that is adjusted or replaced, not voided. Never for the reduction of a delivery
     * corrected to a failure ({@link CorrectionBasis#DELIVERY_FAILED}): the invoice it reduces stopped standing for its
     * shipment when it was drawn up, and the shipment may already carry its new original - voiding it would leave an
     * invoice in force for goods that were not delivered (WF-05 HĐ-3). Refused by the provider, it is reopened,
     * corrected and issued like any draft.
     *
     * @param by null when the system voids it
     * @throws BusinessException {@code EINVOICE_CORRECTION_NOT_APPLICABLE} for a failed delivery's reduction
     */
    public void voidDraft(String reason, UUID by, Instant now) {
        voidDraft(reason, null, by, now);
    }

    /**
     * {@link #voidDraft(String, UUID, Instant)}, and for the reduction of a failed delivery only under a
     * {@link VoidReasonCode} (WF-05 Q-8, BE-29; {@code tg_einvoice_reversal_kept}): one no provider can send, which the
     * accountant makes on the provider's portal and records instead, and one whose delivery was corrected back to
     * delivered before it left. Whether it really cannot be sent is the caller's to decide.
     *
     * @param code null on anything but the reduction of a failed delivery
     * @throws BusinessException {@code EINVOICE_CORRECTION_NOT_APPLICABLE} for a failed delivery's reduction without a
     *         code, or a code on anything else
     */
    public void voidDraft(String reason, VoidReasonCode code, UUID by, Instant now) {
        if (reason == null || reason.isBlank()) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "reason: required to void a draft");
        }
        boolean reduction = correctionBasis != null && correctionBasis.systemOnly();
        if (reduction && code == null) {
            throw new BusinessException(ErrorCode.EINVOICE_CORRECTION_NOT_APPLICABLE, ("Invoice %s reduces an invoice "
                    + "whose delivery was corrected to a failure: it is issued, not voided").formatted(this.code));
        }
        if (!reduction && code != null) {
            throw new BusinessException(ErrorCode.EINVOICE_CORRECTION_NOT_APPLICABLE,
                    "Invoice %s does not reduce a failed delivery: %s does not apply".formatted(this.code, code));
        }
        move(EInvoiceStatus.VOIDED_DRAFT);
        this.voidReasonCode = code;
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
                version, correctionBasis, agreementNumber, agreementDate, returnRequestId, listedInvoiceIds,
                deliveryFailedAt, deliveredOn, reversedAt, issuedVia, portalRecordedBy, portalRecordedAt,
                voidReasonCode);
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
    public boolean adjusted() { return adjusted; }
    public UUID originalEInvoiceId() { return originalEInvoiceId; }
    public CorrectionBasis correctionBasis() { return correctionBasis; }
    public BigDecimal grandTotal() { return grandTotal; }
    public String agreementFileKey() { return agreementFileKey; }
    public List<UUID> listedInvoiceIds() { return listedInvoiceIds; }
    public LocalDate deliveredOn() { return deliveredOn; }
    public Instant reversedAt() { return reversedAt; }
    public IssuedVia issuedVia() { return issuedVia; }
    public VoidReasonCode voidReasonCode() { return voidReasonCode; }
    public int attempt() { return attempt; }
    public Instant submittedAt() { return submittedAt; }
}
