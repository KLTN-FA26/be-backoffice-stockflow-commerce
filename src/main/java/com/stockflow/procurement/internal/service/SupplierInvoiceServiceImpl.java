package com.stockflow.procurement.internal.service;

import com.stockflow.common.api.PageResponse;
import com.stockflow.common.audit.AuditAction;
import com.stockflow.common.audit.Auditable;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.id.Identifiers;
import com.stockflow.common.persistence.Pages;
import com.stockflow.common.persistence.SortWhitelist;
import com.stockflow.common.storage.BufferedUpload;
import com.stockflow.common.storage.DownloadLink;
import com.stockflow.common.storage.FileCategory;
import com.stockflow.common.storage.FileTransfers;
import com.stockflow.common.storage.FileUpload;
import com.stockflow.common.storage.StorageException;
import com.stockflow.common.storage.StoredFile;
import com.stockflow.common.storage.UploadInspection;
import com.stockflow.procurement.api.SubcontractPurchasing;
import com.stockflow.procurement.api.SubcontractSettlement;
import com.stockflow.procurement.internal.domain.InvoiceMatchFacts;
import com.stockflow.procurement.internal.domain.PurchaseOrder;
import com.stockflow.procurement.internal.domain.PurchaseOrderStatus;
import com.stockflow.procurement.internal.domain.SupplierInvoice;
import com.stockflow.procurement.internal.domain.SupplierInvoiceRepository;
import com.stockflow.procurement.internal.domain.SupplierRepository;
import com.stockflow.procurement.internal.domain.ThreeWayMatch;
import com.stockflow.procurement.internal.repository.SupplierInvoiceSearch;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Supplier invoices and their three-way match (SCRUM-308/309).
 *
 * <p>Entering and correcting check the lines against the orders - the supplier's own PO lines, of the invoice's order
 * when it names one, in one currency, receipt lines of the PO line they name - before the aggregate checks the rest;
 * {@code check_supplier_invoice} and {@code check_supplier_invoice_line} say the same in the database.</p>
 *
 * <p>Matching takes the invoice's row lock, then the orders' in id order - the lock receiving and amending take - and
 * only then reads what was received and billed: a receipt posted, or another invoice matched, against the same order
 * waits for it, so two invoices never bill the same received units (kltn-docs 04 BR-02, BR-05).</p>
 */
@Service
@Transactional
class SupplierInvoiceServiceImpl implements SupplierInvoices {

    /** An invoice is dated in the business's calendar, not UTC. */
    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Ho_Chi_Minh");

    /** Nothing was ever approved on these, or the order was called off: there is nothing to bill on it. */
    private static final Set<PurchaseOrderStatus> NOT_INVOICEABLE = Set.of(PurchaseOrderStatus.DRAFT,
            PurchaseOrderStatus.PENDING_APPROVAL, PurchaseOrderStatus.CANCELLED);

    private static final SortWhitelist SORT = SortWhitelist.of("invoiceDate", "invoiceNumber", "dueDate",
            "totalAmount", "status").withDefault("invoiceDate", Sort.Direction.DESC);

    /** No invoice has this id: the excluded one when nothing is to be excluded. */
    private static final UUID NONE = new UUID(0, 0);

    private final SupplierInvoiceRepository invoices;
    private final SupplierInvoiceSearch search;
    private final InvoiceMatchFacts facts;
    private final SupplierRepository suppliers;
    private final SubcontractPurchasing subcontracts;
    private final FileTransfers files;
    private final UploadInspection inspection;
    private final Clock clock;
    private final BigDecimal priceTolerancePercent;

    /**
     * @param priceTolerancePercent how far an invoice's unit price may stray from the order's net price before the
     *                              match calls it a variance (BR-INV-002, kltn-docs 04 BR-03); 2 unless configured
     */
    SupplierInvoiceServiceImpl(SupplierInvoiceRepository invoices, SupplierInvoiceSearch search,
                               InvoiceMatchFacts facts, SupplierRepository suppliers,
                               SubcontractPurchasing subcontracts, FileTransfers files, UploadInspection inspection,
                               Clock clock,
                               @Value("${stockflow.procurement.invoice-match.price-tolerance-percent:2}")
                               BigDecimal priceTolerancePercent) {
        if (priceTolerancePercent.signum() < 0 || priceTolerancePercent.compareTo(BigDecimal.valueOf(100)) > 0) {
            throw new IllegalArgumentException(
                    "stockflow.procurement.invoice-match.price-tolerance-percent is 0 to 100: " + priceTolerancePercent);
        }
        this.invoices = invoices;
        this.search = search;
        this.facts = facts;
        this.suppliers = suppliers;
        this.subcontracts = subcontracts;
        this.files = files;
        this.inspection = inspection;
        this.clock = clock;
        this.priceTolerancePercent = priceTolerancePercent.setScale(2, java.math.RoundingMode.HALF_UP);
    }

    @Override
    @Auditable(action = AuditAction.CREATE, resourceType = "supplier-invoice", resourceId = "#result?.id()")
    public InvoiceView enter(Enter command, UUID by) {
        if (command.supplierId() == null) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "The supplier is required");
        }
        suppliers.findById(command.supplierId()).orElseThrow(() -> new BusinessException(ErrorCode.SUPPLIER_NOT_FOUND,
                "No supplier " + command.supplierId()));
        String currency = checkLines(command.supplierId(), command.purchaseOrderId(), command.currency(),
                command.lines());
        requireNumberFree(command.supplierId(), command.invoiceNumber(), null);
        Instant now = clock.instant();
        SupplierInvoice invoice = SupplierInvoice.enter(Identifiers.newId(), command.supplierId(), currency,
                new SupplierInvoice.Header(command.purchaseOrderId(), command.invoiceNumber(), command.invoiceDate(),
                        command.dueDate(), command.note()), newLines(command.lines()), by, now, today(now),
                Identifiers::newId);
        return view(invoices.save(invoice));
    }

    @Override
    @Auditable(action = AuditAction.UPDATE, resourceType = "supplier-invoice", resourceId = "#invoiceId")
    public InvoiceView revise(UUID invoiceId, Revise command) {
        SupplierInvoice invoice = locked(invoiceId);
        if (command.version() != null && command.version() != invoice.version()) {
            throw new BusinessException(ErrorCode.OPTIMISTIC_LOCK,
                    "Invoice %s changed since it was read".formatted(invoice.invoiceNumber()));
        }
        invoice.requireOpen("changed");
        checkLines(invoice.supplierId(), command.purchaseOrderId(), invoice.currency(), command.lines());
        requireNumberFree(invoice.supplierId(), command.invoiceNumber(), invoice.id());
        invoice.revise(new SupplierInvoice.Header(command.purchaseOrderId(), command.invoiceNumber(),
                command.invoiceDate(), command.dueDate(), command.note()), newLines(command.lines()),
                today(clock.instant()), Identifiers::newId);
        return view(invoices.save(invoice));
    }

    @Override
    @Auditable(action = AuditAction.UPDATE, resourceType = "supplier-invoice", resourceId = "#invoiceId")
    public InvoiceView match(UUID invoiceId, UUID by) {
        SupplierInvoice invoice = locked(invoiceId);
        invoice.requireOpen("matched");
        Set<UUID> poLineIds = invoice.lines().stream().map(SupplierInvoice.Line::purchaseOrderLineId)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        Set<UUID> receiptLineIds = invoice.lines().stream().map(SupplierInvoice.Line::receiptLineId)
                .filter(Objects::nonNull).collect(Collectors.toCollection(LinkedHashSet::new));
        // The orders' locks first, then their lines again: an amendment approved meanwhile changed the price under the
        // same lock, and the match must see the price it left.
        facts.lockOrders(facts.orderLines(poLineIds).values().stream()
                .map(InvoiceMatchFacts.OrderLine::purchaseOrderId).collect(Collectors.toSet()));
        Map<UUID, InvoiceMatchFacts.OrderLine> orderLines = facts.orderLines(poLineIds);

        Map<UUID, ThreeWayMatch.SettlementBasis> settlements = settlements(orderLines.values(), invoice.id());
        Map<UUID, InvoiceMatchFacts.Received> receivedOnPoLines = facts.receivedOnPoLines(poLineIds);
        Map<UUID, Integer> invoicedOnPoLines = facts.invoicedOnPoLines(poLineIds, invoice.id());
        Map<UUID, ThreeWayMatch.PoLineBasis> poBasis = new HashMap<>();
        for (InvoiceMatchFacts.OrderLine line : orderLines.values()) {
            poBasis.put(line.poLineId(), poLineBasis(line, receivedOnPoLines, invoicedOnPoLines, settlements));
        }
        Map<UUID, InvoiceMatchFacts.Received> receivedOnReceiptLines = facts.receivedOnReceiptLines(receiptLineIds);
        Map<UUID, Integer> invoicedOnReceiptLines = facts.invoicedOnReceiptLines(receiptLineIds, invoice.id());
        Map<UUID, ThreeWayMatch.ReceiptLineBasis> receiptBasis = new HashMap<>();
        for (UUID receiptLineId : receiptLineIds) {
            InvoiceMatchFacts.Received received = receivedOnReceiptLines.getOrDefault(receiptLineId,
                    InvoiceMatchFacts.Received.NONE);
            receiptBasis.put(receiptLineId, new ThreeWayMatch.ReceiptLineBasis(receiptLineId, received.billable(),
                    received.awaitingQc(), invoicedOnReceiptLines.getOrDefault(receiptLineId, 0)));
        }

        List<SupplierInvoice.Variance> found = ThreeWayMatch.match(invoice.lines(), poBasis, receiptBasis, settlements,
                priceTolerancePercent, Identifiers::newId);
        invoice.recordMatch(found, priceTolerancePercent, by, clock.instant());
        return view(invoices.save(invoice));
    }

    @Override
    @Auditable(action = AuditAction.UPDATE, resourceType = "supplier-invoice", resourceId = "#invoiceId")
    public InvoiceView cancel(UUID invoiceId, String reason, UUID by) {
        SupplierInvoice invoice = locked(invoiceId);
        invoice.cancel(reason, by, clock.instant());
        return view(invoices.save(invoice));
    }

    /**
     * The scan is read whole first (size, type by its bytes) and inspected before it is stored; storing it is undone
     * if the transaction is ({@link FileTransfers}).
     */
    @Override
    @Auditable(action = AuditAction.UPDATE, resourceType = "supplier-invoice", resourceId = "#invoiceId")
    public InvoiceView attachScan(UUID invoiceId, FileUpload upload) {
        SupplierInvoice invoice = locked(invoiceId);
        try (BufferedUpload buffered = BufferedUpload.read(FileCategory.SUPPLIER_INVOICE, upload)) {
            try (InputStream content = buffered.open()) {
                inspection.requireClean(content);
            }
            try (InputStream content = new BufferedInputStream(buffered.open())) {
                StoredFile file = files.store(FileCategory.SUPPLIER_INVOICE, new FileUpload(upload.originalName(),
                        upload.contentType(), upload.sizeBytes(), content));
                invoice.attach(new SupplierInvoice.Document(file.key(), file.originalName(), file.contentType()));
            }
        } catch (IOException ex) {
            throw new StorageException("Could not read the buffered invoice scan", ex);
        }
        return view(invoices.save(invoice));
    }

    @Override
    @Transactional(readOnly = true)
    public DownloadLink scanLink(UUID invoiceId) {
        SupplierInvoice invoice = found(invoiceId);
        if (invoice.document() == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND,
                    "Invoice %s has no scan attached".formatted(invoice.invoiceNumber()));
        }
        return files.downloadLink(invoice.document().key());
    }

    @Override
    @Transactional(readOnly = true)
    public InvoiceView get(UUID invoiceId) {
        return view(found(invoiceId));
    }

    @Override
    @Transactional(readOnly = true)
    public PageResponse<Row> list(List<com.stockflow.procurement.internal.domain.SupplierInvoiceStatus> statuses,
                                  UUID supplierId, UUID purchaseOrderId, String number, LocalDate invoicedFrom,
                                  LocalDate invoicedTo, int page, int size, String sort) {
        var rows = search.search(new SupplierInvoiceSearch.Criteria(statuses, supplierId, purchaseOrderId, number,
                invoicedFrom, invoicedTo), Pages.of(page, size, SORT.parse(sort)));
        Map<UUID, String> names = search.supplierNames(rows.getContent().stream()
                .map(SupplierInvoiceSearch.Row::supplierId).collect(Collectors.toSet()));
        Map<UUID, Integer> variances = search.varianceCounts(rows.getContent().stream()
                .map(SupplierInvoiceSearch.Row::id).toList());
        return Pages.toResponse(rows.map(row -> new Row(row.id(), row.invoiceNumber(), row.supplierId(),
                names.get(row.supplierId()), row.purchaseOrderId(), row.status(), row.invoiceDate(), row.dueDate(),
                row.currency(), row.totalAmount(), variances.getOrDefault(row.id(), 0))));
    }

    @Override
    @Transactional(readOnly = true)
    public Basis basis(UUID purchaseOrderId) {
        List<InvoiceMatchFacts.OrderLine> lines = facts.orderLinesOf(purchaseOrderId);
        if (lines.isEmpty()) {
            throw new BusinessException(ErrorCode.PURCHASE_ORDER_NOT_FOUND, "No purchase order " + purchaseOrderId);
        }
        Set<UUID> poLineIds = lines.stream().map(InvoiceMatchFacts.OrderLine::poLineId).collect(Collectors.toSet());
        Map<UUID, ThreeWayMatch.SettlementBasis> settlements = settlements(lines, NONE);
        Map<UUID, InvoiceMatchFacts.Received> received = facts.receivedOnPoLines(poLineIds);
        Map<UUID, Integer> invoiced = facts.invoicedOnPoLines(poLineIds, NONE);
        InvoiceMatchFacts.OrderLine first = lines.getFirst();
        boolean subcontract = first.type() == PurchaseOrder.Type.SUBCONTRACT;
        ThreeWayMatch.SettlementBasis settlement = settlements.get(purchaseOrderId);
        return new Basis(purchaseOrderId, first.purchaseOrderNumber(), first.supplierId(), first.orderStatus(),
                first.currency(), subcontract, subcontract ? settlement.settled() : null,
                subcontract && settlement.settled() ? settlement.netPayable() : null, priceTolerancePercent,
                lines.stream().map(line -> {
                    ThreeWayMatch.PoLineBasis basis = poLineBasis(line, received, invoiced, settlements);
                    return new BasisLine(line.poLineId(), line.lineNo(), line.sku(), line.ordered(), basis.billable(),
                            basis.awaitingQc(), basis.invoiced(), line.unitPrice(), line.discountRate(),
                            line.netUnitPrice(), line.taxRate());
                }).toList());
    }

    // ---- helpers --------------------------------------------------------------------------------------------------

    /**
     * Checks every line bills an existing PO line of the supplier - of {@code purchaseOrderId} when the invoice names
     * one - on an order that can be invoiced, in one currency, and that a named receipt line is of the PO line.
     *
     * @return the orders' currency, which the invoice takes
     */
    private String checkLines(UUID supplierId, UUID purchaseOrderId, String requestedCurrency, List<LineInput> lines) {
        if (lines == null || lines.isEmpty()) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "An invoice bills at least one line");
        }
        Set<String> currencies = new LinkedHashSet<>();
        if (purchaseOrderId != null) {
            List<InvoiceMatchFacts.OrderLine> ofOrder = facts.orderLinesOf(purchaseOrderId);
            if (ofOrder.isEmpty()) {
                throw new BusinessException(ErrorCode.PURCHASE_ORDER_NOT_FOUND, "No purchase order " + purchaseOrderId);
            }
            requireInvoiceable(supplierId, ofOrder.getFirst());
            currencies.add(ofOrder.getFirst().currency());
        }
        Set<UUID> poLineIds = lines.stream().map(LineInput::purchaseOrderLineId).filter(Objects::nonNull)
                .collect(Collectors.toSet());
        Map<UUID, InvoiceMatchFacts.OrderLine> orderLines = facts.orderLines(poLineIds);
        for (UUID poLineId : poLineIds) {
            InvoiceMatchFacts.OrderLine line = orderLines.get(poLineId);
            if (line == null) {
                throw new BusinessException(ErrorCode.SUPPLIER_INVOICE_LINE_INVALID, "No purchase order line " + poLineId);
            }
            if (purchaseOrderId != null && !purchaseOrderId.equals(line.purchaseOrderId())) {
                throw new BusinessException(ErrorCode.SUPPLIER_INVOICE_LINE_INVALID, ("Line %d of %s is not of the "
                        + "purchase order the invoice bills").formatted(line.lineNo(), line.purchaseOrderNumber()));
            }
            requireInvoiceable(supplierId, line);
            currencies.add(line.currency());
        }
        Map<UUID, UUID> receiptLines = facts.poLinesOfReceiptLines(lines.stream().map(LineInput::receiptLineId)
                .filter(Objects::nonNull).collect(Collectors.toSet()));
        for (LineInput line : lines) {
            if (line.receiptLineId() != null
                    && !Objects.equals(receiptLines.get(line.receiptLineId()), line.purchaseOrderLineId())) {
                throw new BusinessException(ErrorCode.SUPPLIER_INVOICE_LINE_INVALID,
                        "Receipt line %s is not a delivery of the purchase order line it is billed with"
                                .formatted(line.receiptLineId()));
            }
        }
        if (currencies.size() > 1) {
            throw new BusinessException(ErrorCode.SUPPLIER_INVOICE_CURRENCY_MISMATCH,
                    "The orders it bills are in " + String.join(" and ", currencies) + ": one invoice is in one");
        }
        String currency = currencies.iterator().next();
        if (requestedCurrency != null && !requestedCurrency.isBlank()
                && !currency.equals(requestedCurrency.strip().toUpperCase(Locale.ROOT))) {
            throw new BusinessException(ErrorCode.SUPPLIER_INVOICE_CURRENCY_MISMATCH,
                    "The orders it bills are in %s, not %s".formatted(currency, requestedCurrency));
        }
        return currency;
    }

    private static void requireInvoiceable(UUID supplierId, InvoiceMatchFacts.OrderLine line) {
        if (!supplierId.equals(line.supplierId())) {
            throw new BusinessException(ErrorCode.SUPPLIER_INVOICE_LINE_INVALID,
                    "Purchase order %s is another supplier's".formatted(line.purchaseOrderNumber()));
        }
        if (NOT_INVOICEABLE.contains(line.orderStatus())) {
            throw new BusinessException(ErrorCode.PURCHASE_ORDER_NOT_INVOICEABLE,
                    "Purchase order %s is %s".formatted(line.purchaseOrderNumber(), line.orderStatus()));
        }
    }

    private void requireNumberFree(UUID supplierId, String invoiceNumber, UUID excluding) {
        if (invoiceNumber != null && !invoiceNumber.isBlank()
                && invoices.numberTaken(supplierId, invoiceNumber.strip(), excluding)) {
            throw new BusinessException(ErrorCode.SUPPLIER_INVOICE_NUMBER_TAKEN,
                    "The supplier already has invoice %s".formatted(invoiceNumber.strip()));
        }
    }

    /**
     * A SUBCONTRACT order's settlement per order the lines bill: final or not, its net payable, and what MATCHED
     * invoices billed on the order already.
     */
    private Map<UUID, ThreeWayMatch.SettlementBasis> settlements(Collection<InvoiceMatchFacts.OrderLine> lines,
                                                                 UUID excludingInvoice) {
        Map<UUID, ThreeWayMatch.SettlementBasis> settlements = new HashMap<>();
        for (InvoiceMatchFacts.OrderLine line : lines) {
            if (line.type() != PurchaseOrder.Type.SUBCONTRACT || settlements.containsKey(line.purchaseOrderId())) {
                continue;
            }
            SubcontractSettlement settlement = subcontracts.settlement(line.purchaseOrderId()).orElse(null);
            boolean settled = settlement != null && "FINAL".equals(settlement.status());
            settlements.put(line.purchaseOrderId(), new ThreeWayMatch.SettlementBasis(line.purchaseOrderId(), settled,
                    settled ? settlement.passedQuantity() : 0, settled ? settlement.netPayable() : BigDecimal.ZERO,
                    facts.invoicedAmountOnOrder(line.purchaseOrderId(), excludingInvoice)));
        }
        return settlements;
    }

    /** A SUBCONTRACT line bills what its final settlement passed (BR-PRD-17); a STANDARD one what QC let through. */
    private ThreeWayMatch.PoLineBasis poLineBasis(InvoiceMatchFacts.OrderLine line,
                                                  Map<UUID, InvoiceMatchFacts.Received> received,
                                                  Map<UUID, Integer> invoiced,
                                                  Map<UUID, ThreeWayMatch.SettlementBasis> settlements) {
        boolean subcontract = line.type() == PurchaseOrder.Type.SUBCONTRACT;
        int billable;
        int awaitingQc;
        if (subcontract) {
            billable = settlements.get(line.purchaseOrderId()).passedQuantity();
            awaitingQc = 0;
        } else {
            InvoiceMatchFacts.Received in = received.getOrDefault(line.poLineId(), InvoiceMatchFacts.Received.NONE);
            billable = in.billable();
            awaitingQc = in.awaitingQc();
        }
        return new ThreeWayMatch.PoLineBasis(line.poLineId(), line.purchaseOrderId(), subcontract, line.netUnitPrice(),
                line.taxRate(), billable, awaitingQc, invoiced.getOrDefault(line.poLineId(), 0));
    }

    private static List<SupplierInvoice.NewLine> newLines(List<LineInput> lines) {
        return lines == null ? List.of() : lines.stream().map(line -> new SupplierInvoice.NewLine(
                line.purchaseOrderLineId(), line.receiptLineId(), line.quantity(), line.unitPrice(), line.taxRate()))
                .toList();
    }

    private SupplierInvoice locked(UUID invoiceId) {
        return invoices.findByIdForUpdate(invoiceId).orElseThrow(() -> notFound(invoiceId));
    }

    private SupplierInvoice found(UUID invoiceId) {
        return invoices.findById(invoiceId).orElseThrow(() -> notFound(invoiceId));
    }

    private static BusinessException notFound(UUID invoiceId) {
        return new BusinessException(ErrorCode.SUPPLIER_INVOICE_NOT_FOUND, "No supplier invoice " + invoiceId);
    }

    private static LocalDate today(Instant now) {
        return LocalDate.ofInstant(now, BUSINESS_ZONE);
    }

    private InvoiceView view(SupplierInvoice invoice) {
        Map<UUID, InvoiceMatchFacts.OrderLine> orderLines = facts.orderLines(invoice.lines().stream()
                .map(SupplierInvoice.Line::purchaseOrderLineId).collect(Collectors.toSet()));
        String supplierName = search.supplierNames(Set.of(invoice.supplierId())).get(invoice.supplierId());
        SupplierInvoice.Document scan = invoice.document();
        return new InvoiceView(invoice.id(), invoice.supplierId(), supplierName, invoice.purchaseOrderId(),
                invoice.invoiceNumber(), invoice.invoiceDate(), invoice.dueDate(), invoice.currency(), invoice.note(),
                invoice.status(), invoice.subtotal(), invoice.taxTotal(), invoice.total(),
                invoice.lines().stream().map(line -> {
                    InvoiceMatchFacts.OrderLine of = orderLines.get(line.purchaseOrderLineId());
                    return new LineView(line.id(), line.purchaseOrderLineId(), of == null ? null : of.purchaseOrderId(),
                            of == null ? null : of.purchaseOrderNumber(), of == null ? 0 : of.lineNo(),
                            of == null ? null : of.sku(), line.receiptLineId(), line.quantity(), line.unitPrice(),
                            line.taxRate(), line.netAmount(), line.taxAmount(), line.total());
                }).toList(),
                invoice.variances().stream().map(v -> new VarianceView(v.id(), v.lineId(), v.purchaseOrderId(),
                        v.kind(), v.expected(), v.invoiced(), v.amount())).toList(),
                scan == null ? null : scan.name(), scan == null ? null : scan.contentType(), invoice.receivedAt(),
                invoice.receivedBy(), invoice.matchedAt(), invoice.matchedBy(), invoice.priceTolerancePercent(),
                invoice.cancelledAt(), invoice.cancelledBy(), invoice.cancelReason(), invoice.version());
    }
}
