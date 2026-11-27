package com.stockflow.identity.internal.controller;

import com.stockflow.common.api.ApiResponse;
import com.stockflow.common.security.Action;
import com.stockflow.common.security.AuthenticatedUser;
import com.stockflow.common.security.CurrentUser;
import com.stockflow.common.security.RequiresPermission;
import com.stockflow.common.security.ScopeGuards;
import com.stockflow.identity.internal.controller.dto.ArrangeCoverRequest;
import com.stockflow.identity.internal.service.StaffCoverService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * Covers during leave over HTTP (sales-owner plan BR-S07, D7; API contract "BE-A"). {@code staff-covers:READ} lists,
 * {@code CREATE} arranges, {@code UPDATE} cancels. A salesperson arranges and cancels covers of their own absence; a
 * manager - a holder of {@code customer-sales-owners:UPDATE}, the same "manager" as every screen of the plan - anyone's
 * (the rule is {@link StaffCoverService}'s; this passes whether the caller is one).
 */
@RestController
@RequestMapping("/api/v1/staff-covers")
class StaffCoverController {

    /** customer's resource that makes a caller a manager of the plan; identity may not import customer's constants. */
    private static final String MANAGES_OWNERS_RESOURCE = "customer-sales-owners";

    private final StaffCoverService covers;

    StaffCoverController(StaffCoverService covers) {
        this.covers = covers;
    }

    /** @param active only covers not cancelled whose last day has not passed */
    @GetMapping
    @RequiresPermission(resource = IdentityResources.STAFF_COVERS, action = Action.READ)
    public ApiResponse<List<StaffCoverService.CoverRow>> list(@AuthenticatedUser CurrentUser user,
                                                              @RequestParam(required = false) UUID userId,
                                                              @RequestParam(defaultValue = "false") boolean active) {
        ScopeGuards.requireBackOffice(user);
        return ApiResponse.ok(covers.rows(covers.list(userId, active)));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @RequiresPermission(resource = IdentityResources.STAFF_COVERS, action = Action.CREATE)
    public ApiResponse<StaffCoverService.CoverRow> arrange(@AuthenticatedUser CurrentUser user,
                                                           @Valid @RequestBody ArrangeCoverRequest request) {
        ScopeGuards.requireBackOffice(user);
        var arranged = covers.arrange(new StaffCoverService.ArrangeCover(request.userId(), request.coverUserId(),
                request.from(), request.to(), request.reason()), user.userId(), managesOwners(user));
        return ApiResponse.ok(covers.rows(List.of(arranged)).get(0));
    }

    @PostMapping("/{coverId}/cancellation")
    @RequiresPermission(resource = IdentityResources.STAFF_COVERS, action = Action.UPDATE)
    public ApiResponse<StaffCoverService.CoverRow> cancel(@PathVariable UUID coverId,
                                                          @AuthenticatedUser CurrentUser user) {
        ScopeGuards.requireBackOffice(user);
        var cancelled = covers.cancel(coverId, user.userId(), managesOwners(user));
        return ApiResponse.ok(covers.rows(List.of(cancelled)).get(0));
    }

    private static boolean managesOwners(CurrentUser user) {
        return user.can(MANAGES_OWNERS_RESOURCE, Action.UPDATE);
    }
}
