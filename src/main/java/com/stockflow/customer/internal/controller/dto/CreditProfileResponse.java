package com.stockflow.customer.internal.controller.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** A customer's credit; {@code configured=false} means none was set: no credit. */
@Schema(name = "CreditProfile")
public record CreditProfileResponse(UUID customerId, boolean configured, BigDecimal creditLimit, Integer creditTermDays,
                                    String currency, UUID approvedBy, Instant approvedAt, String note,
                                    @Schema(description = "-1 when none were set; send it back as expectedVersion")
                                    long version) {
}
