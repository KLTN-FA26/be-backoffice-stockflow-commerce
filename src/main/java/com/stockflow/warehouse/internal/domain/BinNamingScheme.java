package com.stockflow.warehouse.internal.domain;

/**
 * How {@link BinGrid} names the bins it generates (BR-14). Only an input to generation: the scheme
 * is not stored, the codes it produced are.
 */
public enum BinNamingScheme {
    /** {@code 01, 02, ...} row by row. */
    SEQUENTIAL,
    /** {@code A01, A02, ..., B01, ...}: a letter per row, so at most 26 rows. */
    ROW_LETTER_COLUMN
}
