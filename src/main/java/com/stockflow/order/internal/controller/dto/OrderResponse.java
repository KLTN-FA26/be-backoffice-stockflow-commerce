package com.stockflow.order.internal.controller.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * API representation of an order.
 *
 * <p>Money is flattened to {@code amount} plus {@code currency} rather than serialising
 * {@code Money} directly. JSON has no money type, and letting Jackson invent a shape for a value
 * object means the wire format changes whenever the record does.</p>
 */
@Schema(name = "Order")
public record OrderResponse(
        UUID orderId,

        @Schema(example = "SO-20260907-000431")
        String orderNumber,

        UUID customerId,

        @Schema(example = "PENDING_PAYMENT")
        String status,

        BigDecimal totalAmount,

        @Schema(example = "VND")
        String currency,

        List<Line> lines,
        Instant placedAt,
        String contactName,
        String contactEmail,
        String contactPhone,
        Address shippingAddress,
        Address billingAddress
) {

    public record Address(String recipientName, String phone, String line1, String line2,
                          String wardCode, String wardName, String provinceCode,
                          String provinceName, String countryCode, String postalCode) {
    }

    @Schema(name = "OrderLine")
    public record Line(
            UUID lineId,
            String sku,
            int quantity,
            BigDecimal unitPrice,
            BigDecimal lineTotal,

            @Schema(description = "The stock holds backing this line - one per lot it is drawn from")
            List<UUID> reservationIds,
            UUID designSnapshotId,
            String designChecksum
    ) {
    }
}
