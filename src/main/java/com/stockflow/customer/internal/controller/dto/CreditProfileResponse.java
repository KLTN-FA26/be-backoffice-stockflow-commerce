package com.stockflow.customer.internal.controller.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** A customer's commercial terms; {@code configured=false} means none were set: prepaid only. */
@Schema(name = "CreditProfile")
public record CreditProfileResponse(UUID customerId, boolean configured, boolean allowPrepaid, boolean allowDeposit,
                                    boolean allowCredit, @Schema(example = "CREDIT") String defaultPaymentTerm,
                                    BigDecimal depositPercent, BigDecimal creditLimit, Integer creditTermDays,
                                    String currency, UUID approvedBy, Instant approvedAt, String note,
                                    @Schema(description = "-1 when none were set; send it back as expectedVersion")
                                    long version) {
}
