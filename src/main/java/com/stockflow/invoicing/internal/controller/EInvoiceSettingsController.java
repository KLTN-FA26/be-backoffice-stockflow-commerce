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
import com.stockflow.invoicing.internal.controller.dto.EInvoiceProviderResponse;
import com.stockflow.invoicing.internal.controller.dto.EInvoiceSettingsResponse;
import com.stockflow.invoicing.internal.controller.dto.EInvoiceVersionRequest;
import com.stockflow.invoicing.internal.controller.dto.StoreEInvoiceProviderAccountRequest;
import com.stockflow.invoicing.internal.controller.dto.UseEInvoiceSeriesRequest;
import com.stockflow.invoicing.internal.service.EInvoiceProviderAccountService;
import com.stockflow.invoicing.internal.service.EInvoiceSettingsService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * The e-invoice settings (WF-05 E2; kltn-docs 21 §2): the provider in use, its series, and whether a complete draft is
 * issued without waiting for the accountant. Read by the accountant, changed by the administrator. Handlers are public
 * so the permission proxy advises them.
 *
 * <p>Package E3 adds the provider itself: a real provider's account, the series it numbers under, and which provider
 * is in use. They sit under the same resource and the same UPDATE as automatic issuing - whoever may let the system
 * issue invoices on its own may say through whom.</p>
 */
@RestController
@RequestMapping("/api/v1/einvoice-settings")
@Tag(name = "E-invoices")
@PermissionResource(code = InvoicingResources.SETTINGS, group = "Invoicing", label = "E-invoice settings",
        route = "/invoicing/settings", apiPath = "/api/v1/einvoice-settings",
        actions = {Action.READ, Action.UPDATE})
class EInvoiceSettingsController {

    private final EInvoiceSettingsService settings;
    private final EInvoiceProviderAccountService providers;

    EInvoiceSettingsController(EInvoiceSettingsService settings, EInvoiceProviderAccountService providers) {
        this.settings = settings;
        this.providers = providers;
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

    @GetMapping("/providers")
    @Operation(summary = "Every e-invoice provider that can be configured, the one in use first")
    @RequiresPermission(resource = InvoicingResources.SETTINGS, action = Action.READ, scope = DataScope.ALL)
    public ApiResponse<List<EInvoiceProviderResponse>> providers(@AuthenticatedUser CurrentUser user) {
        ScopeGuards.requireBackOffice(user);
        return ApiResponse.ok(providers.providers().stream().map(InvoicingWebMapper::toResponse).toList());
    }

    @PutMapping("/providers/{provider}/account")
    @Operation(summary = "Store the account a real provider is called with; the password is sealed and never returned")
    @RequiresPermission(resource = InvoicingResources.SETTINGS, action = Action.UPDATE, scope = DataScope.ALL)
    public ApiResponse<EInvoiceProviderResponse> storeAccount(@PathVariable String provider,
                                                              @Valid @RequestBody
                                                              StoreEInvoiceProviderAccountRequest request,
                                                              @AuthenticatedUser CurrentUser user) {
        ScopeGuards.requireBackOffice(user);
        return ApiResponse.ok(InvoicingWebMapper.toResponse(providers.storeAccount(provider, request.environment(),
                request.username(), request.secret(), request.version())));
    }

    @PutMapping("/providers/{provider}/series")
    @Operation(summary = "Make a series the one a year's invoices are numbered under at a provider")
    @RequiresPermission(resource = InvoicingResources.SETTINGS, action = Action.UPDATE, scope = DataScope.ALL)
    public ApiResponse<Void> useSeries(@PathVariable String provider,
                                       @Valid @RequestBody UseEInvoiceSeriesRequest request,
                                       @AuthenticatedUser CurrentUser user) {
        ScopeGuards.requireBackOffice(user);
        providers.useSeries(provider, request.templateCode(), request.symbol(), request.year(),
                request.providerTemplateCode());
        return ApiResponse.ok(null);
    }

    @PostMapping("/providers/{provider}/activation")
    @Operation(summary = "Issue invoices through this provider from now on; invoices already handed to another stay there")
    @RequiresPermission(resource = InvoicingResources.SETTINGS, action = Action.UPDATE, scope = DataScope.ALL)
    public ApiResponse<EInvoiceProviderResponse> activate(@PathVariable String provider,
                                                          @Valid @RequestBody EInvoiceVersionRequest request,
                                                          @AuthenticatedUser CurrentUser user) {
        ScopeGuards.requireBackOffice(user);
        return ApiResponse.ok(InvoicingWebMapper.toResponse(providers.activate(provider, request.version())));
    }
}
