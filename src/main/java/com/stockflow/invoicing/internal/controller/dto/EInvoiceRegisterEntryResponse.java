package com.stockflow.invoicing.internal.controller.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

/** One issued e-invoice in the register of a period. */
public record EInvoiceRegisterEntryResponse(String templateCode, String symbol, int invoiceNumber, LocalDate issueDate,
                                            String type, String status, boolean adjusted, String buyerName,
                                            String buyerTaxCode, String buyerAddress, String orderNumber,
                                            BigDecimal subtotal, BigDecimal taxTotal, BigDecimal grandTotal,
                                            String taxAuthorityCode, String lookupCode, String originalReference) {
}
