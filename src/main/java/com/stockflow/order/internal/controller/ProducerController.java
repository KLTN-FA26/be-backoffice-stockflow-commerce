package com.stockflow.order.internal.controller;

import com.stockflow.common.api.ApiResponse;
import com.stockflow.common.api.PageResponse;
import com.stockflow.common.persistence.Pages;
import com.stockflow.common.security.Action;
import com.stockflow.common.security.AuthenticatedUser;
import com.stockflow.common.security.CurrentUser;
import com.stockflow.common.security.DataScope;
import com.stockflow.common.security.PermissionResource;
import com.stockflow.common.security.RequiresPermission;
import com.stockflow.common.security.ScopeGuards;
import com.stockflow.order.internal.domain.Producer;
import com.stockflow.order.internal.service.ProducerService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * The producer catalogue (4A-1, kltn-docs 17 §4.3): kept by the Production Coordinator (00 §2.1, 2026-11-07); read by
 * the Sales Manager, who approves production orders, Sales and the warehouse manager.
 *
 * <p>Handler methods are public, as on {@link OrderController}: the permission aspect needs a proxied public method.</p>
 */
@RestController
@RequestMapping("/api/v1/producers")
@Tag(name = "Producers")
@PermissionResource(code = OrderResources.PRODUCERS, group = "Production", label = "Producers",
        route = "/production/producers", apiPath = "/api/v1/producers",
        actions = {Action.VIEW_PAGE, Action.READ, Action.CREATE, Action.UPDATE})
class ProducerController {

    /** A producer as the coordinator enters it. */
    @Schema(name = "ProducerRequest")
    record ProducerRequest(
            @Schema(description = "2-32 letters, digits, '-' or '_'; upper-cased; never changes", example = "IN-MINHPHAT")
            @Size(max = 32, message = "code is at most 32 characters") String code,
            @NotBlank(message = "name is required") @Size(max = 200, message = "name is at most 200 characters")
            String name,
            @Size(max = 200, message = "contactName is at most 200 characters") String contactName,
            @Size(max = 32, message = "contactPhone is at most 32 characters") String contactPhone,
            @Size(max = 254, message = "email is at most 254 characters") String email,
            @Schema(description = "How a production order reaches the producer: EMAIL (sent by the system, needs an "
                    + "address) or PDF (the coordinator hands the sheet over)") @NotNull(message = "channel is required")
            Producer.Channel channel,
            @Schema(description = "Days from sending an order to the goods at the warehouse; default 14")
            @Min(value = 1, message = "standardLeadDays is 1 to 365") @Max(value = 365, message = "standardLeadDays is 1 to 365")
            Integer standardLeadDays,
            @Size(max = 500, message = "note is at most 500 characters") String note,
            @Schema(description = "On update: ACTIVE, or INACTIVE to take no new production order") Producer.Status status,
            @Schema(description = "On update: the version the producer was read at") Long version
    ) {
        Producer.Details details() {
            return new Producer.Details(name, contactName, contactPhone, email, channel, standardLeadDays, note);
        }
    }

    /** A producer of the catalogue. */
    @Schema(name = "Producer")
    record ProducerResponse(UUID id, String code, String name, String contactName, String contactPhone, String email,
                            Producer.Channel channel, int standardLeadDays, Producer.Status status, String note,
                            long version) {
        static ProducerResponse of(Producer p) {
            return new ProducerResponse(p.id(), p.code(), p.name(), p.contactName(), p.contactPhone(), p.email(),
                    p.channel(), p.standardLeadDays(), p.status(), p.note(), p.version());
        }
    }

    private final ProducerService producers;

    ProducerController(ProducerService producers) {
        this.producers = producers;
    }

    @GetMapping
    @Operation(summary = "The producers, by name; search the code or the name, filter by status")
    @RequiresPermission(resource = OrderResources.PRODUCERS, action = Action.READ, scope = DataScope.ALL)
    public ApiResponse<PageResponse<ProducerResponse>> list(@RequestParam(required = false) String q,
                                                            @RequestParam(required = false) Producer.Status status,
                                                            @RequestParam(defaultValue = "0") int page,
                                                            @RequestParam(required = false) Integer size,
                                                            @RequestParam(required = false) String sort,
                                                            @AuthenticatedUser CurrentUser user) {
        ScopeGuards.requireBackOffice(user);
        return ApiResponse.ok(producers.list(q, status, page, size == null ? Pages.DEFAULT_PAGE_SIZE : size, sort)
                .map(ProducerResponse::of));
    }

    @GetMapping("/{id}")
    @Operation(summary = "One producer")
    @RequiresPermission(resource = OrderResources.PRODUCERS, action = Action.READ, scope = DataScope.ALL)
    public ApiResponse<ProducerResponse> one(@PathVariable UUID id, @AuthenticatedUser CurrentUser user) {
        ScopeGuards.requireBackOffice(user);
        return ApiResponse.ok(ProducerResponse.of(producers.get(id)));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Register a producer (Production Coordinator). 409 PRODUCER_CODE_TAKEN")
    @RequiresPermission(resource = OrderResources.PRODUCERS, action = Action.CREATE, scope = DataScope.ALL)
    public ApiResponse<ProducerResponse> register(@Valid @RequestBody ProducerRequest request,
                                                  @AuthenticatedUser CurrentUser user) {
        ScopeGuards.requireBackOffice(user);
        return ApiResponse.ok(ProducerResponse.of(producers.register(request.code(), request.details())));
    }

    @PutMapping("/{id}")
    @Operation(summary = "Edit a producer, or take it out of use (INACTIVE) - read at `version`. The code never changes")
    @RequiresPermission(resource = OrderResources.PRODUCERS, action = Action.UPDATE, scope = DataScope.ALL)
    public ApiResponse<ProducerResponse> update(@PathVariable UUID id, @Valid @RequestBody ProducerRequest request,
                                                @AuthenticatedUser CurrentUser user) {
        ScopeGuards.requireBackOffice(user);
        return ApiResponse.ok(ProducerResponse.of(producers.update(id, request.version() == null ? -1
                : request.version(), request.details(), request.status())));
    }
}
