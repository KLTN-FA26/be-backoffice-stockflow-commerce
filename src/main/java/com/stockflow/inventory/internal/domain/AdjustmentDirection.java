package com.stockflow.inventory.internal.domain;

/**
 * Which way an adjustment of one LPN carton goes (kltn-docs 20 §4.3 step 1; team plan 4C-3): never a number of units,
 * since a carton is adjusted whole (BR-14).
 */
public enum AdjustmentDirection {

    /** The carton leaves stock whole: damaged, lost, a data correction. Only a carton in stock. */
    WRITE_OFF,

    /** A LOST carton found again comes back into stock whole, at its standard count and lot (BR-18). */
    RESTOCK
}
