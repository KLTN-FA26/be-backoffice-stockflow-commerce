package com.stockflow.design.internal.domain;

/**
 * What a row of a design's decision history records (kltn-docs 12 §5.3, BR-13), stored by name in
 * {@code design_decision.decision}. Each constant is added with the step that writes it. Rows written by the staff
 * workflow being replaced keep their own words ({@code SENT_TO_CUSTOMER}, {@code CUSTOMER_CONFIRMED}, ...) and are read
 * as they are.
 */
public enum DesignDecisionType {
    /** The customer started the design. */
    CREATED,
    /** A revision was started from an approved design; the notes name the snapshot. */
    DERIVED_FROM_SNAPSHOT,
    /** The customer deleted the draft. */
    ARCHIVED
}
