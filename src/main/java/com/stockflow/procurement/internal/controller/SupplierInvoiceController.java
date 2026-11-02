package com.stockflow.procurement.internal.controller;

import com.stockflow.common.api.ApiResponse;
import com.stockflow.common.api.PageResponse;
import com.stockflow.common.persistence.Pages;
import com.stockflow.common.security.Action;
import com.stockflow.common.security.AuthenticatedUser;
import com.stockflow.common.security.CurrentUser;
import com.stockflow.common.security.PermissionResource;
import com.stockflow.common.security.RequiresPermission;
import com.stockflow.common.storage.DownloadLink;
import com.stockflow.common.storage.FileUpload;
import com.stockflow.procurement.internal.controller.dto.SupplierInvoiceRequests;
import com.stockflow.procurement.internal.controller.dto.SupplierInvoiceResponses;
import com.stockflow.procurement.internal.domain.SupplierInvoiceStatus;
import com.stockflow.procurement.internal.service.SupplierInvoices;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Supplier invoices (SCRUM-308) and their three-way match against the purchase order and its receipts (SCRUM-309;
 * kltn-docs 04, BR-INV-001/002). The accountant enters an invoice as on the paper, attaches the scan, and matches it:
 * MATCHED, or EXCEPTION with what was found - quantities beyond what was received and passed QC, prices beyond the
 * configured tolerance, another tax rate. Resolving an EXCEPTION and approving payment are SCRUM-310's.
 */
@RestController
@RequestMapping("/api/v1/supplier-invoices")
@Tag(name = "Supplier invoices", description = "Supplier invoices and the three-way match: PO, receipt, invoice")
@PermissionResource(
        code = PurchaseOrderResources.SUPPLIER_INVOICES,
        group = "Procurement",
        label = "Supplier invoices",
        route = "/procurement/supplier-invoices",
        apiPath = "/api/v1/supplier-invoices",
        actions = {Action.VIEW_PAGE, Action.READ, Action.CREATE, Action.UPDATE, Action.APPROVE})
class SupplierInvoiceController {

    private final SupplierInvoices invoices;

    SupplierInvoiceController(SupplierInvoices invoices) {
        this.invoices = invoices;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Enter a supplier invoice (DRAFT) with its lines, as on the paper",
            description = """
                    Each line bills a purchase order line of the supplier (`purchaseOrderLineId`), optionally one \
                    delivery of it (`receiptLineId`); `purchaseOrderId` names the one order the invoice bills, or is \
                    left out for an invoice over several. Amounts are worked out from the lines: net = quantity x \
                    unit price, tax at the line's rate. The invoice number is unique per supplier, case aside \
                    (BR-INV-001). Codes: `SUPPLIER_INVOICE_NUMBER_TAKEN` (409), `SUPPLIER_INVOICE_LINE_INVALID` \
                    (422: a line not of this supplier, of another order than `purchaseOrderId`, or a receipt line of \
                    another PO line), `SUPPLIER_INVOICE_CURRENCY_MISMATCH` (422), `PURCHASE_ORDER_NOT_INVOICEABLE` \
                    (409: a draft, unapproved or cancelled order), `SUPPLIER_NOT_FOUND`, `PURCHASE_ORDER_NOT_FOUND`.""")
    @RequiresPermission(resource = PurchaseOrderResources.SUPPLIER_INVOICES, action = Action.CREATE)
    public ApiResponse<SupplierInvoiceResponses.Invoice> enter(@Valid @RequestBody SupplierInvoiceRequests.Enter request,
                                                               @AuthenticatedUser CurrentUser user) {
        return ApiResponse.ok(SupplierInvoiceWebMapper.toResponse(invoices.enter(new SupplierInvoices.Enter(
                request.supplierId(), request.purchaseOrderId(), request.invoiceNumber(), request.invoiceDate(),
                request.dueDate(), request.currency(), request.note(), lines(request.lines())), user.userId())));
    }

    @GetMapping
    @Operation(summary = "Supplier invoices, newest invoice date first; status may be repeated")
    @RequiresPermission(resource = PurchaseOrderResources.SUPPLIER_INVOICES, action = Action.READ)
    public ApiResponse<PageResponse<SupplierInvoiceResponses.Row>> list(
            @RequestParam(name = "status", required = false) List<SupplierInvoiceStatus> statuses,
            @RequestParam(required = false) UUID supplierId,
            @RequestParam(required = false) UUID purchaseOrderId,
            @RequestParam(required = false) String search,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate invoicedFrom,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate invoicedTo,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(required = false) Integer size,
            @RequestParam(required = false) String sort) {
        return ApiResponse.ok(invoices.list(statuses, supplierId, purchaseOrderId, search, invoicedFrom, invoicedTo,
                page, size == null ? Pages.DEFAULT_PAGE_SIZE : size, sort).map(SupplierInvoiceWebMapper::toResponse));
    }

    @GetMapping("/match-basis/{purchaseOrderId}")
    @Operation(summary = "What an invoice of one order may bill, per line: ordered, received and passed QC, awaiting "
            + "QC, billed already, at which net price and tax rate")
    @RequiresPermission(resource = PurchaseOrderResources.SUPPLIER_INVOICES, action = Action.READ)
    public ApiResponse<SupplierInvoiceResponses.Basis> basis(@PathVariable UUID purchaseOrderId) {
        return ApiResponse.ok(SupplierInvoiceWebMapper.toResponse(invoices.basis(purchaseOrderId)));
    }

    @GetMapping("/{invoiceId}")
    @Operation(summary = "One invoice with its lines and what its last match found")
    @RequiresPermission(resource = PurchaseOrderResources.SUPPLIER_INVOICES, action = Action.READ)
    public ApiResponse<SupplierInvoiceResponses.Invoice> get(@PathVariable UUID invoiceId) {
        return ApiResponse.ok(SupplierInvoiceWebMapper.toResponse(invoices.get(invoiceId)));
    }

    @PutMapping("/{invoiceId}")
    @Operation(summary = "Correct a DRAFT or EXCEPTION invoice: the whole header and lines again; it is DRAFT "
            + "again, to be matched afresh",
            description = "`version` is the one read: a change made meanwhile is refused (`OPTIMISTIC_LOCK`, 409).")
    @RequiresPermission(resource = PurchaseOrderResources.SUPPLIER_INVOICES, action = Action.UPDATE)
    public ApiResponse<SupplierInvoiceResponses.Invoice> revise(@PathVariable UUID invoiceId,
                                                                @Valid @RequestBody SupplierInvoiceRequests.Revise request) {
        return ApiResponse.ok(SupplierInvoiceWebMapper.toResponse(invoices.revise(invoiceId, new SupplierInvoices.Revise(
                request.purchaseOrderId(), request.invoiceNumber(), request.invoiceDate(), request.dueDate(),
                request.note(),
                lines(request.lines()), request.version()))));
    }

    @PostMapping("/{invoiceId}/match")
    @Operation(summary = "Run the three-way match (BR-INV-002): MATCHED, or EXCEPTION with its variances",
            description = """
                    Per line: units beyond what was received and passed QC, less what MATCHED invoices billed \
                    (`OVER_INVOICED`; `AWAITING_QC` when QC has not concluded on them yet; `OVER_INVOICED_RECEIPT` \
                    for the named delivery), a unit price off the order's net price by more than the configured \
                    tolerance (`PRICE`), another tax rate (`TAX_RATE`); for a subcontract order, nothing before its \
                    settlement is final (`SETTLEMENT_PENDING`) and no more than its net payable \
                    (`ABOVE_SETTLEMENT`). Run again on an EXCEPTION once QC concluded or goods arrived.""")
    @RequiresPermission(resource = PurchaseOrderResources.SUPPLIER_INVOICES, action = Action.UPDATE)
    public ApiResponse<SupplierInvoiceResponses.Invoice> match(@PathVariable UUID invoiceId,
                                                               @AuthenticatedUser CurrentUser user) {
        return ApiResponse.ok(SupplierInvoiceWebMapper.toResponse(invoices.match(invoiceId, user.userId())));
    }

    @PostMapping("/{invoiceId}/cancel")
    @Operation(summary = "Cancel a DRAFT or EXCEPTION invoice entered by mistake or withdrawn by the supplier")
    @RequiresPermission(resource = PurchaseOrderResources.SUPPLIER_INVOICES, action = Action.UPDATE)
    public ApiResponse<SupplierInvoiceResponses.Invoice> cancel(
            @PathVariable UUID invoiceId, @Valid @RequestBody SupplierInvoiceRequests.Cancel request,
            @AuthenticatedUser CurrentUser user) {
        return ApiResponse.ok(SupplierInvoiceWebMapper.toResponse(invoices.cancel(invoiceId, request.reason(),
                user.userId())));
    }

    @PostMapping(value = "/{invoiceId}/scan", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(summary = "Attach the invoice as received: PDF, JPEG or PNG, at most 15 MB; replaces an earlier scan")
    @RequiresPermission(resource = PurchaseOrderResources.SUPPLIER_INVOICES, action = Action.UPDATE)
    public ApiResponse<SupplierInvoiceResponses.Invoice> attachScan(@PathVariable UUID invoiceId,
                                                                    @RequestPart("file") MultipartFile file)
            throws IOException {
        try (InputStream content = file.getInputStream()) {
            FileUpload upload = new FileUpload(file.getOriginalFilename() == null ? "invoice"
                    : file.getOriginalFilename(), file.getContentType(), file.getSize(), content);
            return ApiResponse.ok(SupplierInvoiceWebMapper.toResponse(invoices.attachScan(invoiceId, upload)));
        }
    }

    @GetMapping("/{invoiceId}/scan/download-url")
    @Operation(summary = "A short-lived link to the invoice's scan, served by the object store")
    @RequiresPermission(resource = PurchaseOrderResources.SUPPLIER_INVOICES, action = Action.READ)
    public ApiResponse<SupplierInvoiceResponses.ScanLink> scanLink(@PathVariable UUID invoiceId) {
        DownloadLink link = invoices.scanLink(invoiceId);
        return ApiResponse.ok(new SupplierInvoiceResponses.ScanLink(link.url(), link.expiresAt()));
    }

    private static List<SupplierInvoices.LineInput> lines(List<SupplierInvoiceRequests.Line> lines) {
        return lines.stream().map(line -> new SupplierInvoices.LineInput(line.purchaseOrderLineId(),
                line.receiptLineId(), line.quantity(), line.unitPrice(), line.taxRate())).toList();
    }
}
