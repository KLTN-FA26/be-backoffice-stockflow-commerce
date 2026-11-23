package com.stockflow.identity.internal.controller.dto;

import com.stockflow.common.security.DataScope;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/**
 * What the signed-in user may do, for the frontend to show and hide actions with. The server
 * re-checks every call; this only keeps the screen from offering what would be refused.
 */
@Schema(description = "The signed-in user's roles and effective permissions")
public record MyPermissionsResponse(
        List<String> roles,
        @Schema(description = "resource:ACTION codes, sorted",
                example = "[\"procurement-goods-receipts:READ\", \"procurement-goods-receipts:UPDATE\"]")
        List<String> permissions,
        DataScope dataScope,
        @Schema(description = "The warehouses the caller is assigned to, sorted by prefix; only these may be worked "
                + "in when dataScope is WAREHOUSE. Filled only for that scope - a broader scope is not limited to "
                + "warehouses, so the list is empty there - and empty for a caller assigned none.")
        List<AssignedWarehouseResponse> warehouses) {
}
