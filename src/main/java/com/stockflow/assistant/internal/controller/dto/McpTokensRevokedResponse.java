package com.stockflow.assistant.internal.controller.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/** @param revoked how many of the user's tokens were live and are revoked now */
public record McpTokensRevokedResponse(@Schema(example = "2") int revoked) {
}
