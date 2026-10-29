package com.stockflow.identity.internal.controller.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.UUID;

/** One administrator lock / unlock / disable / enable of an account, with why. */
@Schema(name = "UserStatusChange", description = "An administrator's change to an account's status, and the reason")
public record UserStatusChangeResponse(
        UUID id,
        @Schema(example = "LOCK", allowableValues = {"LOCK", "UNLOCK", "DISABLE", "ENABLE"}) String action,
        @Schema(example = "ACTIVE", allowableValues = {"ACTIVE", "LOCKED", "DISABLED"}) String fromStatus,
        @Schema(example = "LOCKED", allowableValues = {"ACTIVE", "LOCKED", "DISABLED"}) String toStatus,
        String reason,
        @Schema(description = "The administrator's account; absent when no caller existed") UUID actorId,
        String actorUsername,
        Instant changedAt) {
}
