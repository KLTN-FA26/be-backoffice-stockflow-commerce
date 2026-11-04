package com.stockflow.fulfillment.internal.domain;

/** Why the carrier could not deliver a shipment (kltn-docs 09 §4.3 step 2 and its edge cases). */
public enum DeliveryFailureReason { CUSTOMER_ABSENT, REFUSED, WRONG_ADDRESS, LOST, DAMAGED, OTHER }
