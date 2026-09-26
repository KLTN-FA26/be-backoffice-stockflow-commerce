package com.stockflow.procurement.internal.controller.dto;

import jakarta.validation.constraints.Size;
import java.time.LocalDate;

public record SendPurchaseOrderRequest(LocalDate expectedAt, @Size(max = 1000) String reason) {}
