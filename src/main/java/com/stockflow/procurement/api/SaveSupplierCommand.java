package com.stockflow.procurement.api;

public record SaveSupplierCommand(String code, String name, String contactName, String email,
                                  String phone, String taxCode, String status, int paymentTermDays,
                                  int leadTimeDays, String communicationChannel, String apiEndpoint) { }
