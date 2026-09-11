package com.stockflow.inventory.internal.controller;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

import java.util.UUID;

/**
 * Request body for a reservation.
 *
 * <p>Bean Validation annotations, not hand-written {@code if} checks in the controller. They run
 * before the method body, produce a uniform 400 through {@code GlobalExceptionHandler}, and show
 * up in the generated OpenAPI document so the frontend sees the same rules the server enforces.</p>
 *
 * <p>Validation here does not replace the checks inside {@code ReserveStockCommand} and the
 * aggregate. This layer rejects malformed input; the domain rejects impossible business states.
 * A caller reaching the service through another module never passes through this class at all.</p>
 */
@Schema(description = "Hold stock for one order line")
public record ReserveStockRequest(

        @Schema(description = "Idempotency key. Resending the same value returns the original "
                + "reservation instead of creating a second one - send a fresh UUID per intended "
                + "reservation, and reuse it on retry.")
        @NotNull(message = "requestId is required")
        UUID requestId,

        @Schema(example = "SOFA-3S-GREY")
        @NotBlank(message = "sku is required")
        @Pattern(regexp = "[A-Za-z0-9\\-]{3,64}", message = "malformed SKU")
        String sku,

        @Schema(example = "2")
        @Min(value = 1, message = "quantity must be at least 1")
        int quantity,

        @NotNull(message = "orderId is required")
        UUID orderId
) {
}
