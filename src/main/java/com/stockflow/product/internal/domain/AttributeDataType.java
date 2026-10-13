package com.stockflow.product.internal.domain;

/**
 * What kind of value an attribute takes. The type decides whether the attribute owns a dictionary
 * of allowed values at all, and whether it can tell variants of one product apart.
 */
public enum AttributeDataType {
    TEXT, NUMBER, BOOLEAN, SELECT, MULTISELECT, COLOR;

    /** Only a closed list of choices has a dictionary; free text, numbers and flags do not. */
    public boolean holdsValues() {
        return this == SELECT || this == MULTISELECT || this == COLOR;
    }

    /** A variant is one pick per axis, so a multi-valued attribute cannot be an axis. */
    public boolean canBeVariantAxis() {
        return this == SELECT || this == COLOR;
    }
}
