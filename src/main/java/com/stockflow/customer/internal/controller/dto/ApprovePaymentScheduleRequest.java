package com.stockflow.customer.internal.controller.dto;

import jakarta.validation.constraints.NotNull;

/** Accounting's approval of a default payment schedule as read (kltn-docs 15 BR-12). */
public record ApprovePaymentScheduleRequest(@NotNull Long expectedVersion) {
}
