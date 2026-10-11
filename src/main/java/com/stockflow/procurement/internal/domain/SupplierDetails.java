package com.stockflow.procurement.internal.domain;

import java.math.BigDecimal;

/**
 * Everything editable about a supplier. The contact (name, email, phone) is the supplier's primary
 * contact, kept in {@code supplier_contacts}; {@code taxCode} is {@code suppliers.tax_id}.
 *
 * @param overReceiptTolerancePercent how far above the ordered quantity a receipt may go (BR-02);
 *                                    null means no tolerance
 * @param printSubcontractor          the supplier prints to order and can take subcontract POs
 * @param lossTolerancePercent        the print waste a subcontract order allows for
 */
public record SupplierDetails(
        String code,
        String name,
        String contactName,
        String email,
        String phone,
        String taxCode,
        SupplierStatus status,
        int paymentTermDays,
        int leadTimeDays,
        SupplierCommunicationChannel communicationChannel,
        String apiEndpoint,
        BigDecimal overReceiptTolerancePercent,
        boolean printSubcontractor,
        BigDecimal lossTolerancePercent) {}
