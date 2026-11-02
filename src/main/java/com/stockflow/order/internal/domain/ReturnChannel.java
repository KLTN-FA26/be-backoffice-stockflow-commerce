package com.stockflow.order.internal.domain;

/**
 * Who opened a return: the customer on the portal, or Sales for them from the back office (SCRUM-243). Mapped
 * {@code EnumType.STRING}. The portal path is held to the customer's own orders and to an ACTIVE account; Sales may
 * open for any customer.
 */
public enum ReturnChannel {
    PORTAL,
    BACK_OFFICE
}
