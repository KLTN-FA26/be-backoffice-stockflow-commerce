package com.stockflow.chat.internal.controller;

import com.stockflow.chat.internal.controller.dto.ChatRequests;
import com.stockflow.chat.internal.service.CannedResponses;
import com.stockflow.common.api.ApiResponse;
import com.stockflow.common.security.Action;
import com.stockflow.common.security.AuthenticatedUser;
import com.stockflow.common.security.CurrentUser;
import com.stockflow.common.security.RequiresPermission;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * Sales' saved replies ({@code chat-canned-responses}, V20261124000300): one's own and the team's shared ones. A body
 * may name {@code {customerName}}, {@code {agentName}}, {@code {quoteCode}}, {@code {orderCode}},
 * {@code {contractCode}}, {@code {productionOrderCode}}, {@code {invoiceCode}}, {@code {designName}}, filled in by the
 * server when it is sent ({@code POST /chat/conversations/{id}/messages} with {@code cannedResponseId}).
 */
@RestController
@RequestMapping("/api/v1/chat/canned-responses")
@Tag(name = "Sales chat (staff)")
class CannedResponseController {

    private final CannedResponses canned;

    CannedResponseController(CannedResponses canned) {
        this.canned = canned;
    }

    @GetMapping
    @Operation(summary = "The caller's own saved replies and the shared ones, shared first; q searches title, body and "
            + "shortcut")
    @RequiresPermission(resource = ChatResources.CANNED_RESPONSES, action = Action.READ)
    public ApiResponse<List<CannedResponses.View>> list(@AuthenticatedUser CurrentUser user,
                                                        @RequestParam(required = false) String q,
                                                        @RequestParam(required = false) String category) {
        return ApiResponse.ok(canned.list(user, q, category));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "A saved reply of the caller's own, or shared=true for every agent (a chat supervisor's)")
    @RequiresPermission(resource = ChatResources.CANNED_RESPONSES, action = Action.CREATE)
    public ApiResponse<CannedResponses.View> create(@AuthenticatedUser CurrentUser user,
                                                    @RequestBody ChatRequests.CannedResponse request) {
        return ApiResponse.ok(canned.create(user, draft(request)));
    }

    @PutMapping("/{id}")
    @Operation(summary = "Rewrite one's own saved reply, or a shared one as a chat supervisor; version: the one read")
    @RequiresPermission(resource = ChatResources.CANNED_RESPONSES, action = Action.UPDATE)
    public ApiResponse<CannedResponses.View> update(@PathVariable UUID id, @AuthenticatedUser CurrentUser user,
                                                    @RequestBody ChatRequests.CannedResponse request) {
        return ApiResponse.ok(canned.update(user, id, draft(request)));
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "Delete (archive) one's own saved reply, or a shared one as a chat supervisor")
    @RequiresPermission(resource = ChatResources.CANNED_RESPONSES, action = Action.DELETE)
    public ApiResponse<Void> delete(@PathVariable UUID id, @AuthenticatedUser CurrentUser user) {
        canned.delete(user, id);
        return ApiResponse.ok(null);
    }

    private static CannedResponses.Draft draft(ChatRequests.CannedResponse request) {
        return new CannedResponses.Draft(request.title(), request.body(), request.shortcut(), request.category(),
                request.shared(), request.version());
    }
}
