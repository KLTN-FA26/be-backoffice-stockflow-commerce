package com.stockflow.procurement.api;

public record RecordSupplierConfirmationCommand(String status, String supplierReference, String note) { }
