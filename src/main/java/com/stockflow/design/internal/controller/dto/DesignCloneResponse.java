package com.stockflow.design.internal.controller.dto;

/**
 * A design cloned onto another variant (FE request 2026-11-13 E-3), and - only when the source is in a project the
 * clone could not join - why: {@code PROJECT_ARCHIVED} or {@code PROJECT_FULL}.
 */
public record DesignCloneResponse(DesignDraftResponse design, String notInProject) {
}
