package com.stockflow.fulfillment.internal.controller;

import com.stockflow.common.api.ApiResponse;
import com.stockflow.common.api.PageResponse;
import com.stockflow.common.persistence.Pages;
import com.stockflow.common.security.Action;
import com.stockflow.common.security.DataScope;
import com.stockflow.common.security.RequiresPermission;
import com.stockflow.fulfillment.internal.controller.dto.TaskCandidateResponse;
import com.stockflow.fulfillment.internal.service.TaskCandidates;
import io.swagger.v3.oas.annotations.Operation;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * The orders the create-task dialog offers. Guarded by the permission to open a task - whoever may open one may see
 * what it can be opened for - and limited to the caller's warehouses.
 */
@RestController
@RequestMapping("/api/v1/fulfillment/task-candidates")
class TaskCandidateController {

    private final TaskCandidates candidates;
    private final TaskCandidateWebMapper mapper;

    TaskCandidateController(TaskCandidates candidates, TaskCandidateWebMapper mapper) {
        this.candidates = candidates;
        this.mapper = mapper;
    }

    @GetMapping
    @Operation(summary = "Orders a fulfillment task may be opened for now (READY_TO_FULFILL), oldest release first")
    @RequiresPermission(resource = FulfillmentResources.TASK_ASSIGNMENT, action = Action.CREATE,
            scope = DataScope.WAREHOUSE)
    public ApiResponse<PageResponse<TaskCandidateResponse>> list(
            @RequestParam(required = false) UUID warehouseId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(required = false) Integer size) {
        return ApiResponse.ok(candidates.candidates(warehouseId, page, size == null ? Pages.DEFAULT_PAGE_SIZE : size)
                .map(mapper::toResponse));
    }
}
