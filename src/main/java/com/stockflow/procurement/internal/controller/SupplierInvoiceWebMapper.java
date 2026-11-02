package com.stockflow.procurement.internal.controller;

import com.stockflow.procurement.internal.controller.dto.SupplierInvoiceResponses;
import com.stockflow.procurement.internal.service.SupplierInvoices;

/** Service views to response bodies for {@link SupplierInvoiceController}. */
final class SupplierInvoiceWebMapper {

    private SupplierInvoiceWebMapper() {
    }

    static SupplierInvoiceResponses.Invoice toResponse(SupplierInvoices.InvoiceView v) {
        return new SupplierInvoiceResponses.Invoice(v.id(), v.supplierId(), v.supplierName(), v.purchaseOrderId(),
                v.invoiceNumber(), v.invoiceDate(), v.dueDate(), v.currency(), v.note(), v.status().name(),
                v.subtotal(), v.taxTotal(), v.total(),
                v.lines().stream().map(l -> new SupplierInvoiceResponses.Line(l.id(), l.purchaseOrderLineId(),
                        l.purchaseOrderId(), l.purchaseOrderNumber(), l.lineNo(), l.sku(), l.receiptLineId(),
                        l.quantity(), l.unitPrice(), l.taxRate(), l.netAmount(), l.taxAmount(), l.total())).toList(),
                v.variances().stream().map(x -> new SupplierInvoiceResponses.Variance(x.id(), x.lineId(),
                        x.purchaseOrderId(), x.kind().name(), x.expected(), x.invoiced(), x.amount())).toList(),
                v.scanName() != null, v.scanName(), v.scanContentType(), v.receivedAt(), v.receivedBy(),
                v.matchedAt(), v.matchedBy(), v.priceTolerancePercent(), v.cancelledAt(), v.cancelledBy(), v.cancelReason(),
                v.version());
    }

    static SupplierInvoiceResponses.Row toResponse(SupplierInvoices.Row r) {
        return new SupplierInvoiceResponses.Row(r.id(), r.invoiceNumber(), r.supplierId(), r.supplierName(),
                r.purchaseOrderId(), r.status().name(), r.invoiceDate(), r.dueDate(), r.currency(), r.total(),
                r.varianceCount());
    }

    static SupplierInvoiceResponses.Basis toResponse(SupplierInvoices.Basis b) {
        return new SupplierInvoiceResponses.Basis(b.purchaseOrderId(), b.purchaseOrderNumber(), b.supplierId(),
                b.status().name(), b.currency(), b.subcontract(), b.settled(), b.settlementNetPayable(),
                b.priceTolerancePercent(), b.lines().stream().map(l -> new SupplierInvoiceResponses.BasisLine(
                        l.purchaseOrderLineId(), l.lineNo(), l.sku(), l.ordered(), l.billable(), l.awaitingQc(),
                        l.invoiced(), l.unitPrice(), l.discountRate(), l.netUnitPrice(), l.taxRate())).toList());
    }
}
