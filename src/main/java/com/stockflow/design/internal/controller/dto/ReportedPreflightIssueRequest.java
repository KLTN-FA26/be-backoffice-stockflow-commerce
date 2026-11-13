package com.stockflow.design.internal.controller.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.stockflow.design.internal.service.DesignSubmissionService;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * One finding of the studio's own preflight, as a browser reports it - for a design ({@link PreflightFailureRequest})
 * and for each design of a project's batch alike. Only the rule and where it is are kept (Q-7): a {@code message} the
 * studio sends along is ignored, as free text a browser wrote is not trusted into the history.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ReportedPreflightIssueRequest(
        @NotBlank @Size(max = DesignSubmissionService.REPORTED_FIELD_MAX) String rule,
        @Size(max = DesignSubmissionService.REPORTED_FIELD_MAX) String surfaceCode,
        @Size(max = DesignSubmissionService.REPORTED_FIELD_MAX) String elementId) {

    public DesignSubmissionService.ReportedIssue issue() {
        return new DesignSubmissionService.ReportedIssue(rule, surfaceCode, elementId);
    }
}
