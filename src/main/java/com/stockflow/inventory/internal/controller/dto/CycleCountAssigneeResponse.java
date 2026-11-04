package com.stockflow.inventory.internal.controller.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;
import java.util.UUID;

/** Someone a count may be handed to: an active warehouse staff member or manager of the count's warehouse. */
@Schema(name = "CycleCountAssignee")
public record CycleCountAssigneeResponse(UUID userId, String displayName, List<String> roles) {
}
