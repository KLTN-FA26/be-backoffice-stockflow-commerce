package com.stockflow.fulfillment.internal.service;

/**
 * Where a load's waybill number came from (kltn-docs 09 §4.4: "(1) Webhook, (2) nhập hàng loạt từ file, (3) nhập tay,
 * (4) từ API đặt xe"), as its history records it ({@code ck_waybill_change_source}).
 */
public enum WaybillSource { MANUAL, FILE, WEBHOOK, API }
