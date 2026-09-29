package com.stockflow.procurement.internal.controller.dto;

import java.time.Instant;
import java.util.UUID;

public record SupplierResponse(UUID supplierId, String code, String name, String contactName,
        String email, String phone, String taxCode, String status, int paymentTermDays,
        int leadTimeDays, String communicationChannel, String apiEndpoint,
        Instant createdAt, Instant lastModifiedAt) { }
