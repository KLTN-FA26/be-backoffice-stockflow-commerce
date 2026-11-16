package com.stockflow.order.internal.controller;

import com.stockflow.common.api.ApiResponse;
import com.stockflow.common.security.Action;
import com.stockflow.common.security.DataScope;
import com.stockflow.common.security.PermissionResource;
import com.stockflow.common.security.RequiresPermission;
import com.stockflow.order.internal.domain.CapacityBoard;
import com.stockflow.order.internal.service.CapacityBoardService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.UUID;

/**
 * The warehouse capacity board (kltn-docs 06 §4.6; SCRUM-669). Under {@code /warehouses} because that is what it
 * describes; in {@code order} because that is where the facts can be composed ({@link CapacityBoardService} says why).
 * A caller limited to warehouses reads only their own (BR-SEC-002).
 *
 * <p>Figures are rounded only here, for display - m³ to 3 decimals, kg to 1 - and the sums behind them are exact.</p>
 *
 * <p>Handler methods are public: the permission aspect needs a proxied public method.</p>
 */
@RestController
@RequestMapping("/api/v1/warehouses")
@Tag(name = "Warehouse capacity")
@PermissionResource(code = OrderResources.WAREHOUSE_CAPACITY, group = "Warehouse", label = "Capacity board",
        route = "/warehouse/capacity", apiPath = "/api/v1/warehouses/{warehouseId}/capacity",
        actions = {Action.VIEW_PAGE, Action.READ})
class CapacityBoardController {

    @Schema(name = "CapacityLoad", description = "Cartons, their volume and full weight; unmeasuredCartons of them "
            + "have no full carton size, so the volume is low by that much")
    record LoadResponse(long cartons, BigDecimal volumeM3, BigDecimal weightKg, long unmeasuredCartons) {

        static LoadResponse of(CapacityBoard.Load load) {
            return new LoadResponse(load.cartons(), m3(load.volumeM3()), kg(load.weightKg()), load.unmeasuredCartons());
        }
    }

    @Schema(name = "CapacitySection", description = "A zone (shelfCode null) or a shelf; zoneId and name null for "
            + "shelves in no zone")
    record SectionResponse(UUID zoneId, String name, String shelfCode, BigDecimal capacityM3, BigDecimal capacityKg,
                           LoadResponse occupied, BigDecimal freeM3, BigDecimal freeKg, BigDecimal occupiedPercent) {

        static SectionResponse of(CapacityBoard.Section s) {
            return new SectionResponse(s.zoneId(), s.name(), s.shelfCode(), m3(s.capacityM3()), kg(s.capacityKg()),
                    LoadResponse.of(s.occupied()), m3(s.freeM3()), kg(s.freeKg()), s.occupiedPercent());
        }
    }

    @Schema(name = "CapacityArea", description = "An area has no capacity (06 BR-19); its cartons count as occupied "
            + "unless it is a staging or dispatch area, whose cartons are leaving")
    record AreaResponse(String locationCode, String code, String name, CapacityBoard.AreaKind type,
                        LoadResponse occupied, boolean countsAsOccupied) {

        static AreaResponse of(CapacityBoard.AreaRow a) {
            return new AreaResponse(a.locationCode(), a.code(), a.name(), a.kind(), LoadResponse.of(a.occupied()),
                    a.countsAsOccupied());
        }
    }

    @Schema(name = "CapacityBoard")
    record BoardResponse(
            UUID warehouseId, String prefix, BigDecimal utilizationFactor,
            @Schema(description = "Σ volume of usable bins × utilizationFactor") BigDecimal capacityM3,
            @Schema(description = "Σ declared load limit of usable bins × utilizationFactor") BigDecimal capacityKg,
            LoadResponse occupied,
            @Schema(description = "Production orders sent and not fully received") LoadResponse incoming,
            @Schema(description = "Production orders not sent yet (draft, pending approval)") LoadResponse pendingApproval,
            @Schema(description = "Held for orders not yet delivered; reference only, never added to free space")
            LoadResponse outbound,
            @Schema(description = "capacity − occupied − incoming; may be negative") BigDecimal expectedFreeM3,
            @Schema(description = "expected free − pending approval; may be negative") BigDecimal freeIfAllApprovedM3,
            BigDecimal freeKg, BigDecimal occupiedPercent, BigDecimal expectedFreePercent, BigDecimal occupiedKgPercent,
            @Schema(description = "usable bins on a level without a clear height: not in capacity") int binsWithoutVolume,
            int binsWithoutWeightLimit, long unmeasuredCartons,
            List<SectionResponse> zones, List<SectionResponse> shelves, List<AreaResponse> areas) {

        static BoardResponse of(CapacityBoard b) {
            return new BoardResponse(b.warehouseId(), b.prefix(), b.utilizationFactor(), m3(b.capacityM3()),
                    kg(b.capacityKg()), LoadResponse.of(b.occupied()), LoadResponse.of(b.incoming()),
                    LoadResponse.of(b.pendingApproval()), LoadResponse.of(b.outbound()), m3(b.expectedFreeM3()),
                    m3(b.freeIfAllApprovedM3()), kg(b.freeKg()), b.occupiedPercent(), b.expectedFreePercent(),
                    b.occupiedKgPercent(), b.binsWithoutVolume(), b.binsWithoutWeightLimit(), b.unmeasuredCartons(),
                    b.zones().stream().map(SectionResponse::of).toList(),
                    b.shelves().stream().map(SectionResponse::of).toList(),
                    b.areas().stream().map(AreaResponse::of).toList());
        }
    }

    private final CapacityBoardService boards;

    CapacityBoardController(CapacityBoardService boards) {
        this.boards = boards;
    }

    @GetMapping("/{warehouseId}/capacity")
    @Operation(summary = "The capacity board of a warehouse: bin capacity in m³ and kg, occupied, incoming, pending "
            + "approval, expected free, outbound for reference, by zone, shelf and area (kltn-docs 06 §4.6)")
    @RequiresPermission(resource = OrderResources.WAREHOUSE_CAPACITY, action = Action.READ, scope = DataScope.ALL)
    public ApiResponse<BoardResponse> board(@PathVariable UUID warehouseId) {
        return ApiResponse.ok(BoardResponse.of(boards.boardOf(warehouseId)));
    }

    private static BigDecimal m3(BigDecimal value) {
        return value.setScale(3, RoundingMode.HALF_UP);
    }

    private static BigDecimal kg(BigDecimal value) {
        return value.setScale(1, RoundingMode.HALF_UP);
    }
}
