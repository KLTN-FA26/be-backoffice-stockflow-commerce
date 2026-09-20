package com.stockflow.customer.api;

public record ListCustomersQuery(int page, int size, String search, String status, String sort) {
}
