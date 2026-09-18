package com.stockflow.procurement.api;

import java.time.Instant;
import java.util.UUID;

public record SupplierSummary(UUID supplierId, String code, String name, String contactName,
                              String email, String phone, String taxCode, String status,
                              int paymentTermDays, int leadTimeDays, String communicationChannel,
                              String apiEndpoint, Instant createdAt, Instant lastModifiedAt) { }
