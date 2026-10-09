package com.stockflow.warehouse.internal.service;

import com.stockflow.warehouse.internal.domain.WarehouseStatus;

/**
 * @param search matched against prefix and name; {@code null} for all
 * @param status {@code null} for any
 * @param sort   {@code property[,desc]}, from {@code prefix}, {@code name}, {@code createdAt}
 */
public record ListWarehousesQuery(int page, int size, String search, WarehouseStatus status, String sort) {
}
