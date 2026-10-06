package com.stockflow.design.api;

/** A confirmed bundle may contain several files, each with a distinct operational purpose. */
public enum DesignArtifactRole {
    CUSTOMER_PREVIEW,
    TECHNICAL_SPEC,
    PRINT_READY,
    PRODUCTION_INSTRUCTION
}
