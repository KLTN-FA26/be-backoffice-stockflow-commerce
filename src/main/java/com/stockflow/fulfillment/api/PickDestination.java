package com.stockflow.fulfillment.api;

/**
 * Where a pick task takes its cartons (kltn-docs 07 §1, §3 input "destination"; 17 §4.4, BR-08), chosen by the
 * warehouse manager when the task is created and changed only while it is PENDING (07 BR-08). Mapped
 * {@code EnumType.STRING} ({@code ck_pick_destination}).
 */
public enum PickDestination {

    /**
     * The dispatch area, the default: the order may leave now. Each carton is deducted when its LPN is scanned
     * (CONSUMED) and is checked and labelled where it arrives (06 BR-22).
     */
    DISPATCH,

    /**
     * The task's staging area: the order may not leave yet (waiting for a payment due before delivery, or for the rest
     * of its goods). Each carton is only moved there, whole, still held for the order and out of available to promise
     * (20 §4.9, BR-20); it is deducted when the warehouse manager moves the whole task to dispatch (07 §4.3 step 8).
     */
    STAGING
}
