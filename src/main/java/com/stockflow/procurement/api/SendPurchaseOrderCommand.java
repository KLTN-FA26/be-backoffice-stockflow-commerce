package com.stockflow.procurement.api;

import java.time.LocalDate;

/** Explicit date correction before the first outbound snapshot; never silently recomputed. */
public record SendPurchaseOrderCommand(LocalDate expectedAt, String reason) {}
