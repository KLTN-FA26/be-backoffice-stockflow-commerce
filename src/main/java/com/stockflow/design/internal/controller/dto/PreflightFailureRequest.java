package com.stockflow.design.internal.controller.dto;

import com.stockflow.design.internal.service.DesignSubmissionService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * A submission the studio's preflight refused (A-3): the design's {@code version} as the studio read it, and 1 to
 * {@value DesignSubmissionService#REPORTED_ISSUES_MAX} findings.
 */
public record PreflightFailureRequest(
        @PositiveOrZero long version,
        @NotNull @Size(min = 1, max = DesignSubmissionService.REPORTED_ISSUES_MAX)
        List<@NotNull @Valid ReportedPreflightIssueRequest> issues) {

    public List<DesignSubmissionService.ReportedIssue> reported() {
        return issues.stream().map(ReportedPreflightIssueRequest::issue).toList();
    }
}
