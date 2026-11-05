package com.stockflow.procurement.internal.domain;

/**
 * What a document attached to a purchase order is ({@code ck_purchase_order_attachments_category}): the supplier's
 * quotation, the signed contract, the supplier's confirmation, a delivery note, or anything else.
 */
public enum PurchaseOrderAttachmentCategory {
    QUOTATION, CONTRACT, SUPPLIER_CONFIRMATION, DELIVERY_NOTE, OTHER
}
