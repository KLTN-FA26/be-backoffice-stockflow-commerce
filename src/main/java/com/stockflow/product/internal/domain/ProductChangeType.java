package com.stockflow.product.internal.domain;

/**
 * What an entry of the product's history says happened (SCRUM-461, {@code ck_product_change_log_type}).
 * Each type belongs to one kind of subject, which the table also checks.
 */
public enum ProductChangeType {

    CREATED(Subject.PRODUCT),
    UPDATED(Subject.PRODUCT),
    SUBMITTED(Subject.PRODUCT),
    APPROVED(Subject.PRODUCT),
    REJECTED(Subject.PRODUCT),
    SUSPENDED(Subject.PRODUCT),
    RESUMED(Subject.PRODUCT),
    DISCONTINUED(Subject.PRODUCT),
    VARIANT_ADDED(Subject.VARIANT),
    VARIANT_UPDATED(Subject.VARIANT),
    VARIANT_STATUS(Subject.VARIANT),
    MEDIA(Subject.MEDIA),
    LOGISTICS(Subject.LOGISTICS),
    INVENTORY_CONTROL(Subject.INVENTORY_CONTROL),
    PURCHASING(Subject.PURCHASING),
    UOM_CONVERSION(Subject.UOM_CONVERSION),
    SALES_UNIT(Subject.SALES_UNIT);

    /**
     * What changed: the product itself, a variant, an image, or the logistics / stock policy / purchasing
     * information / units of measure / sales units of a SKU (V20261016001300).
     */
    public enum Subject {
        PRODUCT, VARIANT, MEDIA, LOGISTICS, INVENTORY_CONTROL, PURCHASING, UOM_CONVERSION, SALES_UNIT
    }

    private final Subject subject;

    ProductChangeType(Subject subject) {
        this.subject = subject;
    }

    public Subject subject() {
        return subject;
    }
}
