package com.stockflow.order.internal.controller.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * One salesperson's row of the workload board (sales-owner plan §10.4; API contract "Workload"). {@code coverUserId}
 * is an addition to the contract, so the screen can link the stand-in.
 */
public record SalesWorkloadResponse(
        UUID userId,
        String name,
        @Schema(description = "Paused for new customers (D13): suggested for none, a manager may still choose them")
        boolean paused,
        @Schema(description = "New customers a month they are meant to take; null for no limit")
        Integer capacityPerMonth,
        @Schema(description = "A cover is in force today (BR-S07)")
        boolean onLeave,
        @Schema(description = "Who covers for them today; null unless onLeave")
        UUID coverUserId,
        @Schema(description = "The cover's name; null unless onLeave")
        String coverName,
        @Schema(description = "The cover's last day, included; null unless onLeave")
        LocalDate coverUntil,
        @Schema(description = "Customers they own now")
        int customers,
        @Schema(description = "Of those, customers with an order under way")
        int customersWithOpenOrders,
        @Schema(description = "Open quotes of their customers: draft, sent and valid, changes asked, accepted")
        int openQuotes,
        @Schema(description = "Contracts of their customers sent and not signed")
        int contractsAwaitingSignature,
        @Schema(description = "Sales signed in the last 30 days, by the owner recorded on the contract (14 BR-26)")
        BigDecimal revenue30d,
        @Schema(description = "Customers given to them since the first of the month")
        int newCustomersThisMonth) {
}
