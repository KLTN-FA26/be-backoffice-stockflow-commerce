package com.stockflow.identity.internal.controller.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "How many other sessions were ended")
public record SessionsEndedResponse(int revokedSessions) {
}
