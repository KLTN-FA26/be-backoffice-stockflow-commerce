package com.stockflow.order.internal.controller;

import com.stockflow.common.api.ApiResponse;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.persistence.Pages;
import com.stockflow.common.security.Action;
import com.stockflow.common.security.AuthenticatedUser;
import com.stockflow.common.security.CurrentUser;
import com.stockflow.common.security.DataScope;
import com.stockflow.common.security.RequiresPermission;
import com.stockflow.common.security.ScopeGuards;
import com.stockflow.order.internal.controller.dto.SalesContactResponse;
import com.stockflow.order.internal.service.SalesContactService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * The salesperson column of the back office customer list (owner decision 2026-11-27), read once per page beside
 * {@code GET /customers}, as {@code GET /orders/credit-positions} is: the customer module cannot read quotes or
 * contracts (order depends on customer), so the documents answer here.
 *
 * <p>Guarded by {@code customer-customers:READ}, the customer list's own grant: whoever may read the list may read who
 * the customer deals with. The answer names staff and carries no money, so it does not take the credit grant
 * ({@code customer-credit-profiles:READ}, which is about what a customer owes and may be withheld from a role that reads
 * the list), and {@code sales-quotes:READ} would hide it from the accountant, who reads the list.
 * A portal customer holds {@code customer-customers:READ} for their own profile and is refused by
 * {@link ScopeGuards#requireBackOffice}: this names other customers' salespeople.</p>
 */
@RestController
@RequestMapping("/api/v1/orders")
@Tag(name = "Orders")
class SalesContactController {

    private final SalesContactService contacts;

    SalesContactController(SalesContactService contacts) {
        this.contacts = contacts;
    }

    @GetMapping("/sales-contacts")
    @Operation(summary = "For each customer asked, the salesperson who last dealt with them: who sent their most recent "
            + "quote or contract, and whether that account is still active",
            description = "Derived on read (kltn-docs 00 §2: a document's salesperson is whoever sent it). One item per "
                    + "distinct customer with a sent quote or contract, in the order asked; a customer with neither, or "
                    + "an unknown id, has no item. Two reads whatever the number of customers. Needs "
                    + "customer-customers:READ (the customer list's grant); a portal customer gets 403. 400 "
                    + "UNSUPPORTED_PARAMETER past 200 ids.")
    @RequiresPermission(resource = "customer-customers", action = Action.READ, scope = DataScope.ALL)
    public ApiResponse<List<SalesContactResponse>> salesContacts(
            @AuthenticatedUser CurrentUser user,
            @Parameter(description = "The customers, comma separated (customerIds=a,b,c) or the parameter "
                    + "repeated (customerIds=a&customerIds=b), not both: typically the ids of one page of GET /customers; "
                    + "at most 200")
            @RequestParam List<UUID> customerIds) {
        ScopeGuards.requireBackOffice(user);
        if (customerIds.size() > Pages.MAX_PAGE_SIZE) {
            throw new BusinessException(ErrorCode.UNSUPPORTED_PARAMETER, "At most %d customers, got %d".formatted(
                    Pages.MAX_PAGE_SIZE, customerIds.size()));
        }
        return ApiResponse.ok(contacts.of(customerIds).stream().map(c -> new SalesContactResponse(c.customerId(),
                c.salesUserId(), c.salesName(), c.salesActive(), c.source().name(), c.documentNumber(), c.at()))
                .toList());
    }
}
