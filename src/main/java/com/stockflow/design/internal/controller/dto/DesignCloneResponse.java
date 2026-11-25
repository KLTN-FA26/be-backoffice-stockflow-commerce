package com.stockflow.design.internal.controller.dto;

/**
 * A design cloned onto another variant (FE request 2026-11-13 E-3). {@code notInProject} once said why a clone stood
 * outside its source's closed or full project; since BR-23.3 a clone is written into a project or refused, so it is
 * always null, kept only so the answer keeps its shape for the clients that read it.
 */
public record DesignCloneResponse(DesignDraftResponse design, String notInProject) {
}
