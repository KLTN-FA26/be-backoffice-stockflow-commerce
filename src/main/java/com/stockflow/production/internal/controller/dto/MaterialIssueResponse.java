package com.stockflow.production.internal.controller.dto;

import com.stockflow.production.internal.service.MaterialIssues;

/** The issue a scan made (or the one a retry found) and the production order after it. */
public record MaterialIssueResponse(MaterialIssues.Issued issue, ProductionOrderResponse order) {
}
