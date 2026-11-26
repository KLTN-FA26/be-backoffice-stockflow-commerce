package com.stockflow.inventory.api;

/**
 * Which way an adjustment of one LPN carton goes (kltn-docs 20 §4.3). Never a number of units: a carton is adjusted
 * whole (BR-14), and how many units it stands for is the carton's to say.
 */
public enum StockAdjustmentDirection {

    /** The carton leaves stock whole. */
    WRITE_OFF,

    /** A LOST carton found again comes back into stock whole. */
    RESTOCK
}
