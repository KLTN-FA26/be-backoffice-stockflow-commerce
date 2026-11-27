package com.stockflow.customer.internal.controller;

import com.stockflow.common.api.ApiResponse;
import com.stockflow.common.api.PageResponse;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.persistence.Pages;
import com.stockflow.common.ratelimit.RateLimit;
import com.stockflow.common.security.Action;
import com.stockflow.common.security.AuthenticatedUser;
import com.stockflow.common.security.CurrentUser;
import com.stockflow.common.security.RequiresPermission;
import com.stockflow.common.security.ScopeGuards;
import com.stockflow.customer.internal.controller.dto.SalesOwnerRequests;
import com.stockflow.customer.internal.domain.Lead;
import com.stockflow.customer.internal.domain.LeadStatus;
import com.stockflow.customer.internal.service.LeadService;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * Leads (sales-owner plan §10.3 channel B, §10.7): the public form, and the back office's queue and steps.
 *
 * <p>The form is anonymous ({@code permitAll} for POST in the security configuration), limited per address, and always
 * answers {@code 202 {received: true}} once the body is well formed - whether a lead was made, a repeat dropped, the
 * honeypot caught, or the tax code found to be a customer's - so it tells nobody whether a business is a customer. A
 * malformed body (no consent, a field past its limit, a null among the interests) is a 400, decided before anything
 * depends on who the business is, so it says nothing about anyone. Whatever goes wrong after that - even a server
 * error - is logged and answered {@code 202} too (finding S1): an error only one of the two paths could raise would
 * otherwise tell a customer's tax code from a newcomer's.</p>
 *
 * <p>Back office: {@code sales-leads:READ} / {@code UPDATE}; a manager sees and assigns every lead, a salesperson works
 * the leads given to them (checked in {@link LeadService}).</p>
 */
@RestController
class LeadController {

    private static final Logger log = LoggerFactory.getLogger(LeadController.class);

    private final LeadService leads;

    LeadController(LeadService leads) {
        this.leads = leads;
    }

    @PostMapping("/api/v1/public/leads")
    @ResponseStatus(HttpStatus.ACCEPTED)
    @RateLimit(limit = 5, perSeconds = 600, key = RateLimit.Key.IP)
    public ApiResponse<SalesOwnerRequests.LeadReceipt> submit(@Valid @RequestBody SalesOwnerRequests.SubmitLead request) {
        Lead.Form form = new Lead.Form(request.companyName().strip(), blankToNull(request.taxCode()),
                request.contactName().strip(), blankToNull(request.contactTitle()), blankToNull(request.phone()),
                blankToNull(request.email()), blankToNull(request.province()), request.interests(),
                blankToNull(request.monthlyVolume()), blankToNull(request.note()));
        try {
            leads.submit(form, request.website());
        } catch (RuntimeException failed) {
            // Class names only: a database error's detail can carry what the sender typed.
            Throwable root = failed;
            while (root.getCause() != null && root.getCause() != root) {
                root = root.getCause();
            }
            log.error("Lead form failed after it was accepted ({}, caused by {}); the sender is answered as always",
                    failed.getClass().getName(), root.getClass().getName());
        }
        return ApiResponse.ok(new SalesOwnerRequests.LeadReceipt(true));
    }

    @GetMapping("/api/v1/leads")
    @RequiresPermission(resource = CustomerResources.SALES_LEADS, action = Action.READ)
    public ApiResponse<PageResponse<LeadService.LeadRow>> list(@AuthenticatedUser CurrentUser user,
                                                               @RequestParam(required = false) String status,
                                                               @RequestParam(required = false) UUID assignedTo,
                                                               @RequestParam(defaultValue = "0") int page,
                                                               @RequestParam(required = false) Integer size) {
        ScopeGuards.requireBackOffice(user);
        return ApiResponse.ok(leads.list(status(status), assignedTo, page,
                size == null ? Pages.DEFAULT_PAGE_SIZE : size, user.userId()));
    }

    @GetMapping("/api/v1/leads/{leadId}")
    @RequiresPermission(resource = CustomerResources.SALES_LEADS, action = Action.READ)
    public ApiResponse<LeadService.LeadRow> get(@PathVariable UUID leadId, @AuthenticatedUser CurrentUser user) {
        ScopeGuards.requireBackOffice(user);
        return ApiResponse.ok(leads.get(leadId, user.userId()));
    }

    @GetMapping("/api/v1/leads/{leadId}/history")
    @RequiresPermission(resource = CustomerResources.SALES_LEADS, action = Action.READ)
    public ApiResponse<List<LeadService.HistoryRow>> history(@PathVariable UUID leadId,
                                                             @AuthenticatedUser CurrentUser user) {
        ScopeGuards.requireBackOffice(user);
        return ApiResponse.ok(leads.history(leadId, user.userId()));
    }

    @PutMapping("/api/v1/leads/{leadId}/assignment")
    @RequiresPermission(resource = CustomerResources.SALES_LEADS, action = Action.UPDATE)
    public ApiResponse<LeadService.LeadRow> assign(@PathVariable UUID leadId, @AuthenticatedUser CurrentUser user,
                                                   @Valid @RequestBody SalesOwnerRequests.AssignLead request) {
        ScopeGuards.requireBackOffice(user);
        return ApiResponse.ok(leads.assign(leadId, request.salesUserId(), user.userId()));
    }

    @PostMapping("/api/v1/leads/{leadId}/contact")
    @RequiresPermission(resource = CustomerResources.SALES_LEADS, action = Action.UPDATE)
    public ApiResponse<LeadService.LeadRow> contact(@PathVariable UUID leadId, @AuthenticatedUser CurrentUser user,
                                                    @Valid @RequestBody SalesOwnerRequests.ContactLead request) {
        ScopeGuards.requireBackOffice(user);
        return ApiResponse.ok(leads.contact(leadId, request.note(), user.userId()));
    }

    @PostMapping("/api/v1/leads/{leadId}/disqualification")
    @RequiresPermission(resource = CustomerResources.SALES_LEADS, action = Action.UPDATE)
    public ApiResponse<LeadService.LeadRow> disqualify(@PathVariable UUID leadId, @AuthenticatedUser CurrentUser user,
                                                       @Valid @RequestBody SalesOwnerRequests.DisqualifyLead request) {
        ScopeGuards.requireBackOffice(user);
        return ApiResponse.ok(leads.disqualify(leadId, request.reason(), user.userId()));
    }

    private static LeadStatus status(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return LeadStatus.valueOf(value.strip());
        } catch (IllegalArgumentException unknown) {
            throw new BusinessException(ErrorCode.UNSUPPORTED_PARAMETER, "Unknown lead status " + value);
        }
    }

    private static String blankToNull(String text) {
        return text == null || text.isBlank() ? null : text.strip();
    }
}
