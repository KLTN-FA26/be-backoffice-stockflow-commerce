package com.stockflow.order.internal.controller.dto;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
public final class DesignQuoteRequests {
    private DesignQuoteRequests(){}
    public record Create(@NotNull UUID requestId,@NotNull UUID customerId,@NotNull UUID designSnapshotId,
                         @NotBlank @Size(max=64) String sku,@Min(1) int quantity,@NotNull @DecimalMin("1") BigDecimal unitPrice,
                         @NotNull Instant validUntil,@NotBlank @Size(max=2000) String terms){}
    public record Revision(@NotNull @Min(0) Long version,@Min(1) int quantity,@NotNull @DecimalMin("1") BigDecimal unitPrice,
                           @NotNull Instant validUntil,@NotBlank @Size(max=2000) String terms){}
    public record Version(@NotNull @Min(0) Long version){}
    public record Reason(@NotNull @Min(0) Long version,@NotBlank @Size(max=2000) String reason){}
}
