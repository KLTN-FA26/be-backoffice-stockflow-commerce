package com.stockflow.notification.internal.service;

import com.stockflow.contracts.OrderCancelled;
import com.stockflow.contracts.OrderPlaced;
import com.stockflow.contracts.ShipmentStatusChanged;
import com.stockflow.notification.internal.domain.EmailProvider;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/** Properties and events the customer e-mail tests share. */
final class EmailFixtures {

    static final String FROM = "StockFlow <no-reply@stockflow.vn>";

    private EmailFixtures() {
    }

    static EmailProperties properties(String replyTo, boolean customerEnabled, int maxAttempts) {
        return new EmailProperties(EmailProvider.RECORDING, FROM, replyTo, Locale.forLanguageTag("vi"), "StockFlow",
                new EmailProperties.Customer(customerEnabled, maxAttempts, Duration.ofMinutes(1), Duration.ofHours(6)),
                new EmailProperties.Resend(null, "https://api.resend.com", null, Duration.ofMinutes(5), 2, 2));
    }

    static EmailProperties properties() {
        return properties(null, true, 10);
    }

    static OrderPlaced placed(UUID customerId, String contactEmail) {
        return new OrderPlaced(UUID.randomUUID(), customerId, contactEmail, "SO-20261024-0001",
                List.of(new OrderPlaced.OrderLine("CUP-PET-500", 2000, new BigDecimal("625.00"), null),
                        new OrderPlaced.OrderLine("LID-PET-90", 2000, new BigDecimal("125"), null)),
                new BigDecimal("1500000.00"), "VND");
    }

    static ShipmentStatusChanged shipment(UUID customerId, String status, String failureReason, String carrier) {
        return new ShipmentStatusChanged(UUID.randomUUID(), UUID.randomUUID(), "SO-20261024-0001-S1",
                UUID.randomUUID(), "SO-20261024-0001", customerId, UUID.randomUUID(), "PARCEL", "READY_TO_DISPATCH",
                status, failureReason, 3, carrier, "LD-20261024-0001", UUID.randomUUID(),
                Instant.parse("2026-10-24T07:05:00Z"));
    }

    static OrderCancelled cancelled(UUID customerId, String reasonCode, BigDecimal refundable, BigDecimal owed) {
        return new OrderCancelled(UUID.randomUUID(), "SO-20261024-0001", customerId, "CONFIRMED", reasonCode,
                "Khách gọi điện: giao nhầm kho, gọi lại cho anh Ba 0901234567", new BigDecimal("2000000"), refundable,
                "VND", UUID.randomUUID(), Instant.parse("2026-10-24T16:30:00Z"), owed);
    }
}
