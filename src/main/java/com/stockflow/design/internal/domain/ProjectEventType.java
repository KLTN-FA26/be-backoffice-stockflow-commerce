package com.stockflow.design.internal.domain;

/**
 * What a row of a design project's history records (kltn-docs 12 §5.4, BR-13), stored by name in
 * {@code design_project_event.event}; {@code ck_design_project_event_type} lists the same. What happens to a design
 * in the project is in the design's own history ({@link DesignDecisionType}).
 */
public enum ProjectEventType {
    /** The customer created the project. */
    PROJECT_CREATED,
    /** A file joined the library; the notes name it. */
    ASSET_ADDED,
    /** A file left the library; the notes name it. Designs it was placed on keep their copy (BR-18). */
    ASSET_REMOVED,
    /** A text and its type style joined the library; the notes name it. */
    TEXT_ADDED,
    /** A text left the library; the notes name it. Designs it was placed on keep their own copy of it. */
    TEXT_REMOVED,
    /** A batch was refused because some designs did not pass the preflight; the notes list them (BR-19). */
    BATCH_PREFLIGHT_FAILED,
    /** A batch passed and waits for review as one round; the notes name the round and its designs. */
    BATCH_SUBMITTED,
    /** Every design of a round was decided; the notes give each outcome (BR-20). */
    BATCH_CLOSED,
    /** The customer asked one quote over approved designs of the project; the notes give the lines (BR-21). */
    PROJECT_QUOTE_REQUESTED,
    /** The customer closed the project (BR-22). */
    PROJECT_ARCHIVED
}
