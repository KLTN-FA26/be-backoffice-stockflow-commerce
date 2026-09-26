package com.stockflow.procurement.internal.domain;

/** State can represent a historical incomplete profile; aggregate commands validate new writes. */
public record SupplierDetails(String code, String name, String contactName, String email, String phone,
        String taxCode, SupplierStatus status, int paymentTermDays, int leadTimeDays,
        SupplierCommunicationChannel communicationChannel, String apiEndpoint) {}
