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
    /** Only read: logistics are edited on the inventory item now and nothing writes this; old rows remain. */
    LOGISTICS(Subject.LOGISTICS),
    /** Only read: the stock policy is edited on the inventory item now and nothing writes this; old rows remain. */
    INVENTORY_CONTROL(Subject.INVENTORY_CONTROL);

    /** What changed: the product itself, a variant, an image, or the logistics / stock policy of a SKU. */
    public enum Subject {
        PRODUCT, VARIANT, MEDIA, LOGISTICS, INVENTORY_CONTROL
    }

    private final Subject subject;

    ProductChangeType(Subject subject) {
        this.subject = subject;
    }

    public Subject subject() {
        return subject;
    }
}
