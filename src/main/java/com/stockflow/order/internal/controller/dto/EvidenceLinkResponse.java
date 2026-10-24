package com.stockflow.order.internal.controller.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/** A short-lived link to one photo of a return, fetched straight from object storage (never from the API's origin). */
@Schema(name = "ReturnEvidenceLink")
public record EvidenceLinkResponse(String url) {
}
