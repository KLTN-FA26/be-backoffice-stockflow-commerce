package com.stockflow.procurement.internal.domain;

import com.stockflow.common.domain.AggregateRoot;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * A supplier's invoice as the accountant entered it (SCRUM-308), and what the last three-way match found (SCRUM-309;
 * kltn-docs 04, docs 03 §4).
 *
 * <p>Amounts are worked out here from the lines, never taken from the caller: a line's net is quantity x unit price,
 * its tax that net at the line's rate (rounded half up to the cent), and the invoice's subtotal, tax and total their
 * sums ({@code ck_supplier_invoices_amounts}). The accountant compares the total with the paper; a difference is a
 * typing error to correct before matching.</p>
 *
 * <p>Whether the lines bill the supplier's own PO lines, in the order's currency, is the service's to check against
 * the orders (and {@code check_supplier_invoice_line}'s); what was received and billed already is the match's
 * ({@link ThreeWayMatch}). This holds what stays true of the invoice by itself: its number and dates, positive
 * quantities, cent prices, rates within 0..100, and which status allows what.</p>
 */
public final class SupplierInvoice extends AggregateRoot {

    private static final int MAX_LINES = 200;
    private static final int MONEY_SCALE = 2;
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    /** What the accountant types from the paper, for one line. */
    public record NewLine(UUID purchaseOrderLineId, UUID receiptLineId, int quantity, BigDecimal unitPrice,
                          BigDecimal taxRate) {
    }

    /** One billed line: so many units of a PO line, optionally of one receipt line, at a price and a tax rate. */
    public record Line(UUID id, UUID purchaseOrderLineId, UUID receiptLineId, int quantity, BigDecimal unitPrice,
                       BigDecimal taxRate) {

        public Line {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(purchaseOrderLineId, "purchaseOrderLineId");
            Objects.requireNonNull(unitPrice, "unitPrice");
            Objects.requireNonNull(taxRate, "taxRate");
        }

        /** Quantity x unit price, before tax. */
        public BigDecimal netAmount() {
            return unitPrice.multiply(BigDecimal.valueOf(quantity)).setScale(MONEY_SCALE, RoundingMode.HALF_UP);
        }

        /** The net at the line's tax rate, rounded half up to the cent. */
        public BigDecimal taxAmount() {
            return netAmount().multiply(taxRate).divide(HUNDRED, MONEY_SCALE, RoundingMode.HALF_UP);
        }

        /** Net plus tax: {@code supplier_invoice_lines.line_total}, as a purchase order line's total includes its tax. */
        public BigDecimal total() {
            return netAmount().add(taxAmount());
        }
    }

    /**
     * One thing the last match found wrong: of a line, or - {@link InvoiceVarianceKind#ABOVE_SETTLEMENT} - of a
     * subcontract order ({@code purchaseOrderId}). {@code amount} is the money at stake before tax, signed: positive
     * when the supplier bills more than expected.
     */
    public record Variance(UUID id, UUID lineId, UUID purchaseOrderId, InvoiceVarianceKind kind, BigDecimal expected,
                           BigDecimal invoiced, BigDecimal amount) {

        public Variance {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(kind, "kind");
            if ((kind == InvoiceVarianceKind.ABOVE_SETTLEMENT) != (lineId == null)) {
                throw new IllegalArgumentException("A settlement variance is of an order, every other of a line");
            }
        }
    }

    /**
     * What the accountant may correct on the header while the invoice is open - the order it names too: an invoice
     * entered against the wrong order is corrected, not cancelled, since its number stays taken (BR-INV-001).
     * {@code purchaseOrderId} null: the invoice bills lines of several of the supplier's orders.
     */
    public record Header(UUID purchaseOrderId, String invoiceNumber, LocalDate invoiceDate, LocalDate dueDate,
                         String note) {
    }

    /** The scanned invoice in object storage. */
    public record Document(String key, String name, String contentType) {

        public Document {
            Objects.requireNonNull(key, "key");
        }
    }

    /** Everything a stored invoice is rebuilt from. */
    public record State(UUID id, UUID supplierId, UUID purchaseOrderId, String currency, String invoiceNumber,
                        LocalDate invoiceDate, LocalDate dueDate, String note, SupplierInvoiceStatus status,
                        List<Line> lines, List<Variance> variances, Document document, Instant receivedAt,
                        UUID receivedBy, Instant matchedAt, UUID matchedBy, BigDecimal priceTolerancePercent,
                        Instant cancelledAt, UUID cancelledBy, String cancelReason, long version) {
    }

    private final UUID id;
    private final UUID supplierId;
    private final String currency;
    private final Instant receivedAt;
    private final UUID receivedBy;
    private final long version;

    private UUID purchaseOrderId;
    private String invoiceNumber;
    private LocalDate invoiceDate;
    private LocalDate dueDate;
    private String note;
    private SupplierInvoiceStatus status;
    private List<Line> lines;
    private List<Variance> variances;
    private Document document;
    private Instant matchedAt;
    private UUID matchedBy;
    private BigDecimal priceTolerancePercent;
    private Instant cancelledAt;
    private UUID cancelledBy;
    private String cancelReason;

    public SupplierInvoice(State state) {
        this.id = Objects.requireNonNull(state.id(), "id");
        this.supplierId = Objects.requireNonNull(state.supplierId(), "supplierId");
        this.purchaseOrderId = state.purchaseOrderId();
        this.currency = Objects.requireNonNull(state.currency(), "currency");
        this.invoiceNumber = state.invoiceNumber();
        this.invoiceDate = state.invoiceDate();
        this.dueDate = state.dueDate();
        this.note = state.note();
        this.status = Objects.requireNonNull(state.status(), "status");
        this.lines = List.copyOf(state.lines());
        this.variances = List.copyOf(state.variances());
        this.document = state.document();
        this.receivedAt = state.receivedAt();
        this.receivedBy = state.receivedBy();
        this.matchedAt = state.matchedAt();
        this.matchedBy = state.matchedBy();
        this.priceTolerancePercent = state.priceTolerancePercent();
        this.cancelledAt = state.cancelledAt();
        this.cancelledBy = state.cancelledBy();
        this.cancelReason = state.cancelReason();
        this.version = state.version();
    }

    /**
     * An invoice as entered: DRAFT, not matched yet. The header's {@code purchaseOrderId} is the one order it bills,
     * or null for an invoice over several orders of the supplier (kltn-docs 04 edge case "NCC gộp nhiều PO"); its lines
     * name the PO lines either way.
     *
     * @param today the business day: an invoice is not dated after it
     * @throws BusinessException {@code VALIDATION_FAILED} for a blank number, a future or missing date, a due date
     *         before it, no line, a quantity under 1, a price or rate out of range, or a PO and receipt line twice
     */
    public static SupplierInvoice enter(UUID id, UUID supplierId, String currency, Header header,
                                        List<NewLine> lines, UUID by, Instant now, LocalDate today,
                                        java.util.function.Supplier<UUID> ids) {
        SupplierInvoice invoice = new SupplierInvoice(new State(id, supplierId, null, currency, null, null,
                null, null, SupplierInvoiceStatus.DRAFT, List.of(), List.of(), null, now,
                Objects.requireNonNull(by, "by"), null, null, null, null, null, null, 0L));
        invoice.write(header, lines, today, ids);
        return invoice;
    }

    /**
     * The accountant corrects the header and the lines - the whole set each time. An EXCEPTION goes back to DRAFT
     * and its variances are dropped: they were found on the lines as they were, and are matched afresh.
     *
     * @throws BusinessException {@code SUPPLIER_INVOICE_INVALID_TRANSITION} unless DRAFT or EXCEPTION; as
     *         {@link #enter}
     */
    public void revise(Header header, List<NewLine> newLines, LocalDate today, java.util.function.Supplier<UUID> ids) {
        requireOpen("changed");
        write(header, newLines, today, ids);
        clearMatch();
    }

    /**
     * Records a three-way match: no variance makes it MATCHED (its lines now count as billed against the receipts),
     * any makes it EXCEPTION with the list (BR-INV-002). The tolerance the match used is kept with it.
     *
     * @throws BusinessException {@code SUPPLIER_INVOICE_INVALID_TRANSITION} unless DRAFT or EXCEPTION
     */
    public void recordMatch(List<Variance> found, BigDecimal tolerancePercent, UUID by, Instant at) {
        requireOpen("matched");
        Set<UUID> own = new HashSet<>();
        lines.forEach(line -> own.add(line.id()));
        for (Variance variance : found) {
            if (variance.lineId() != null && !own.contains(variance.lineId())) {
                throw new IllegalArgumentException("A variance of another invoice's line");
            }
        }
        this.variances = List.copyOf(found);
        this.status = found.isEmpty() ? SupplierInvoiceStatus.MATCHED : SupplierInvoiceStatus.EXCEPTION;
        this.matchedAt = Objects.requireNonNull(at, "at");
        this.matchedBy = Objects.requireNonNull(by, "by");
        this.priceTolerancePercent = Objects.requireNonNull(tolerancePercent, "tolerancePercent");
    }

    /**
     * Cancels an invoice entered by mistake or withdrawn by the supplier (kltn-docs 04 "Cancelled"). A MATCHED one is not
     * cancelled here: it already counts as billed, and taking it back is the resolution story's (SCRUM-310).
     *
     * @throws BusinessException {@code VALIDATION_FAILED} without a reason; {@code SUPPLIER_INVOICE_INVALID_TRANSITION}
     *         unless DRAFT or EXCEPTION
     */
    public void cancel(String reason, UUID by, Instant at) {
        requireOpen("cancelled");
        String why = trimmed(reason);
        if (why == null) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "Say why the invoice is cancelled");
        }
        if (why.length() > 500) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "The reason is at most 500 characters");
        }
        this.status = SupplierInvoiceStatus.CANCELLED;
        this.cancelReason = why;
        this.cancelledBy = Objects.requireNonNull(by, "by");
        this.cancelledAt = Objects.requireNonNull(at, "at");
    }

    /**
     * Attaches the scanned invoice, replacing an earlier scan - which stays in storage: it is a financial record.
     * Allowed until the invoice is CANCELLED or PAID: a MATCHED invoice may still be waiting for its paper copy.
     *
     * @throws BusinessException {@code SUPPLIER_INVOICE_INVALID_TRANSITION} when CANCELLED or PAID
     */
    public void attach(Document scan) {
        if (status == SupplierInvoiceStatus.CANCELLED || status == SupplierInvoiceStatus.PAID) {
            throw transition("given a scan");
        }
        this.document = Objects.requireNonNull(scan, "scan");
    }

    private void write(Header header, List<NewLine> newLines, LocalDate today, java.util.function.Supplier<UUID> ids) {
        String number = trimmed(header.invoiceNumber());
        if (number == null) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "The supplier's invoice number is required");
        }
        if (number.length() > 64) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "An invoice number is at most 64 characters");
        }
        if (header.invoiceDate() == null) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "The invoice date is required");
        }
        if (header.invoiceDate().isAfter(today)) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "An invoice dated %s is after today (%s)".formatted(header.invoiceDate(), today));
        }
        if (header.dueDate() != null && header.dueDate().isBefore(header.invoiceDate())) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "The due date is before the invoice date");
        }
        String text = trimmed(header.note());
        if (text != null && text.length() > 500) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "The note is at most 500 characters");
        }
        if (newLines == null || newLines.isEmpty()) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "An invoice bills at least one line");
        }
        if (newLines.size() > MAX_LINES) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "An invoice holds at most %d lines".formatted(MAX_LINES));
        }
        Set<List<UUID>> seen = new HashSet<>();
        List<Line> written = new ArrayList<>();
        for (NewLine line : newLines) {
            if (line.purchaseOrderLineId() == null) {
                throw new BusinessException(ErrorCode.VALIDATION_FAILED, "Every line bills a purchase order line");
            }
            if (!seen.add(java.util.Arrays.asList(line.purchaseOrderLineId(), line.receiptLineId()))) {
                throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                        "Purchase order line %s is billed twice for the same receipt line"
                                .formatted(line.purchaseOrderLineId()));
            }
            if (line.quantity() < 1) {
                throw new BusinessException(ErrorCode.VALIDATION_FAILED, "A line bills at least 1 unit");
            }
            written.add(new Line(ids.get(), line.purchaseOrderLineId(), line.receiptLineId(), line.quantity(),
                    cents(line.unitPrice()), rate(line.taxRate())));
        }
        this.purchaseOrderId = header.purchaseOrderId();
        this.invoiceNumber = number;
        this.invoiceDate = header.invoiceDate();
        this.dueDate = header.dueDate();
        this.note = text;
        this.lines = List.copyOf(written);
    }

    private void clearMatch() {
        this.status = SupplierInvoiceStatus.DRAFT;
        this.variances = List.of();
        this.matchedAt = null;
        this.matchedBy = null;
        this.priceTolerancePercent = null;
    }

    /**
     * Refuses unless DRAFT or EXCEPTION, saying what was attempted: callers check it before reading the orders, so a
     * closed invoice is refused for being closed rather than for whatever else the request carries.
     *
     * @throws BusinessException {@code SUPPLIER_INVOICE_INVALID_TRANSITION}
     */
    public void requireOpen(String what) {
        if (!status.isOpen()) {
            throw transition(what);
        }
    }

    private BusinessException transition(String what) {
        return new BusinessException(ErrorCode.SUPPLIER_INVOICE_INVALID_TRANSITION,
                "Invoice %s is %s and cannot be %s".formatted(invoiceNumber, status, what));
    }

    private static BigDecimal cents(BigDecimal price) {
        if (price == null || price.signum() < 0) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "A unit price is 0 or more");
        }
        BigDecimal stripped = price.stripTrailingZeros();
        if (stripped.scale() > MONEY_SCALE || price.compareTo(new BigDecimal("9999999999999999.99")) > 0) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "A unit price has at most 2 decimals");
        }
        return price.setScale(MONEY_SCALE, RoundingMode.UNNECESSARY);
    }

    private static BigDecimal rate(BigDecimal taxRate) {
        BigDecimal value = taxRate == null ? BigDecimal.ZERO : taxRate;
        if (value.signum() < 0 || value.compareTo(HUNDRED) > 0 || value.stripTrailingZeros().scale() > 2) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "A tax rate is 0 to 100, with at most 2 decimals");
        }
        return value.setScale(2, RoundingMode.UNNECESSARY);
    }

    private static String trimmed(String text) {
        if (text == null) {
            return null;
        }
        String value = text.strip();
        return value.isEmpty() ? null : value;
    }

    /** Sum of the lines' nets. */
    public BigDecimal subtotal() {
        return lines.stream().map(Line::netAmount).reduce(BigDecimal.ZERO.setScale(MONEY_SCALE), BigDecimal::add);
    }

    /** Sum of the lines' taxes. */
    public BigDecimal taxTotal() {
        return lines.stream().map(Line::taxAmount).reduce(BigDecimal.ZERO.setScale(MONEY_SCALE), BigDecimal::add);
    }

    public BigDecimal total() {
        return subtotal().add(taxTotal());
    }

    public UUID id() { return id; }
    public UUID supplierId() { return supplierId; }
    public UUID purchaseOrderId() { return purchaseOrderId; }
    public String currency() { return currency; }
    public String invoiceNumber() { return invoiceNumber; }
    public LocalDate invoiceDate() { return invoiceDate; }
    public LocalDate dueDate() { return dueDate; }
    public String note() { return note; }
    public SupplierInvoiceStatus status() { return status; }
    public List<Line> lines() { return lines; }
    public List<Variance> variances() { return variances; }
    public Document document() { return document; }
    public Instant receivedAt() { return receivedAt; }
    public UUID receivedBy() { return receivedBy; }
    public Instant matchedAt() { return matchedAt; }
    public UUID matchedBy() { return matchedBy; }
    public BigDecimal priceTolerancePercent() { return priceTolerancePercent; }
    public Instant cancelledAt() { return cancelledAt; }
    public UUID cancelledBy() { return cancelledBy; }
    public String cancelReason() { return cancelReason; }
    public long version() { return version; }
}
