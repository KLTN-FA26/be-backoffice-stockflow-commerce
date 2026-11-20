package com.stockflow.design.internal.controller;

import com.stockflow.common.api.ApiResponse;
import com.stockflow.common.security.Action;
import com.stockflow.common.security.RequiresPermission;
import com.stockflow.design.internal.controller.dto.DesignFontResponse;
import com.stockflow.product.api.PrintConfigs;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The fonts the design studio offers (FE request BE-18), read by the storefront's studio and by the back office when it
 * narrows a print configuration's {@code allowedFontCodes}. Guarded by reading designs, the one permission both a
 * customer who designs and the Sales Manager who keeps the configurations already hold; the list names no customer's
 * data. Handlers are public so the permission proxy advises them.
 */
@RestController
@Tag(name = "Design fonts", description = "The fonts the design studio offers")
class DesignFontController {

    private final PrintConfigs printConfigs;

    DesignFontController(PrintConfigs printConfigs) {
        this.printConfigs = printConfigs;
    }

    @GetMapping("/api/v1/design-fonts")
    @Operation(summary = "The fonts the design studio offers, in its order")
    @RequiresPermission(resource = DesignResources.DESIGNS, action = Action.READ)
    public ApiResponse<List<DesignFontResponse>> fonts() {
        return ApiResponse.ok(printConfigs.fonts().stream()
                .map(font -> new DesignFontResponse(font.code(), font.label())).toList());
    }
}
