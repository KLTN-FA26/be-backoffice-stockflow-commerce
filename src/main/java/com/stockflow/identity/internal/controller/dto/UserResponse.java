package com.stockflow.identity.internal.controller.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** One account in user management. Never carries credentials. */
@Schema(name = "User", description = "An account as user management sees it")
public record UserResponse(
        UUID userId,
        String username,
        String email,
        String fullName,
        @Schema(example = "ACTIVE", allowableValues = {"ACTIVE", "LOCKED", "DISABLED"}) String status,
        List<String> roles,
        @Schema(description = "Warehouses a warehouse-bound user may act on (SCRUM-457)")
        List<UUID> warehouseIds,
        @Schema(description = "The password was set by an administrator and must be changed")
        boolean mustChangePassword,
        @Schema(description = "Set while a lock after too many wrong passwords runs")
        Instant lockedUntil,
        Instant lastLoginAt,
        Instant createdAt,
        String createdBy,
        @Schema(description = "The last administrator lock / unlock / disable / enable and its reason; absent when "
                + "none was recorded") UserStatusChangeResponse latestStatusChange,
        @Schema(description = "Send it back with an edit") long version,
        @Schema(description = "The warehouses of warehouseIds, in the same order, with their code and name, so the "
                + "screen need not read the warehouse list (which needs warehouse-warehouses:READ) to name them")
        List<Warehouse> warehouses) {

    @Schema(name = "UserWarehouse", description = "A warehouse the account works in")
    public record Warehouse(UUID warehouseId,
                            @Schema(description = "Its code (the location prefix); null if it no longer exists",
                                    example = "HCM", nullable = true) String code,
                            @Schema(nullable = true) String name) {
    }
}
