package com.stockflow.invoicing.internal.controller;

import com.stockflow.common.api.ApiResponse;
import com.stockflow.common.security.Action;
import com.stockflow.common.security.AuthenticatedUser;
import com.stockflow.common.security.CurrentUser;
import com.stockflow.common.security.DataScope;
import com.stockflow.common.security.PermissionResource;
import com.stockflow.common.security.RequiresPermission;
import com.stockflow.common.security.ScopeGuards;
import com.stockflow.invoicing.internal.controller.dto.ChangeEInvoiceSettingsRequest;
import com.stockflow.invoicing.internal.controller.dto.EInvoiceSettingsResponse;
import com.stockflow.invoicing.internal.service.EInvoiceSettingsService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The e-invoice settings (WF-05 E2; kltn-docs 21 §2): the provider in use, its series, and whether a complete draft is
 * issued without waiting for the accountant. Read by the accountant, changed by the administrator. Handlers are public
 * so the permission proxy advises them.
 */
@RestController
@RequestMapping("/api/v1/einvoice-settings")
@Tag(name = "E-invoices")
@PermissionResource(code = InvoicingResources.SETTINGS, group = "Invoicing", label = "E-invoice settings",
        route = "/invoicing/settings", apiPath = "/api/v1/einvoice-settings",
        actions = {Action.READ, Action.UPDATE})
class EInvoiceSettingsController {

    private final EInvoiceSettingsService settings;

    EInvoiceSettingsController(EInvoiceSettingsService settings) {
        this.settings = settings;
    }

    @GetMapping
    @Operation(summary = "The e-invoice provider in use, its series and whether complete drafts are issued automatically")
    @RequiresPermission(resource = InvoicingResources.SETTINGS, action = Action.READ, scope = DataScope.ALL)
    public ApiResponse<EInvoiceSettingsResponse> get(@AuthenticatedUser CurrentUser user) {
        ScopeGuards.requireBackOffice(user);
        return ApiResponse.ok(InvoicingWebMapper.toResponse(settings.get()));
    }

    @PutMapping
    @Operation(summary = "Turn automatic issuing of complete drafts on or off")
    @RequiresPermission(resource = InvoicingResources.SETTINGS, action = Action.UPDATE, scope = DataScope.ALL)
    public ApiResponse<EInvoiceSettingsResponse> change(@Valid @RequestBody ChangeEInvoiceSettingsRequest request,
                                                        @AuthenticatedUser CurrentUser user) {
        ScopeGuards.requireBackOffice(user);
        return ApiResponse.ok(InvoicingWebMapper.toResponse(settings.changeAutoIssue(request.autoIssue(),
                request.version())));
    }
}
