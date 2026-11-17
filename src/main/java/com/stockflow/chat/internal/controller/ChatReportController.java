package com.stockflow.chat.internal.controller;

import com.stockflow.chat.internal.service.ChatReports;
import com.stockflow.common.api.ApiResponse;
import com.stockflow.common.security.Action;
import com.stockflow.common.security.AuthenticatedUser;
import com.stockflow.common.security.CurrentUser;
import com.stockflow.common.security.RequiresPermission;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.UUID;

/** The chat supervisor's figures (kltn-docs 16 §3 Output "Chỉ số", BR-09), under {@code chat-supervision:READ}. */
@RestController
@RequestMapping("/api/v1/chat/reports")
@Tag(name = "Sales chat (staff)")
class ChatReportController {

    private final ChatReports reports;

    ChatReportController(ChatReports reports) {
        this.reports = reports;
    }

    @GetMapping("/agents")
    @Operation(summary = "Per agent and for everyone, over the conversations opened from `from` to `to` (days, "
            + "included; at most 366): first-response time in business minutes, late first answers, resolution time, "
            + "CSAT average and distribution")
    @RequiresPermission(resource = ChatResources.SUPERVISION, action = Action.READ)
    public ApiResponse<ChatReports.Report> agents(@AuthenticatedUser CurrentUser user,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) UUID agentId) {
        return ApiResponse.ok(reports.report(user, from, to, agentId));
    }
}
