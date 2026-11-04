package com.stockflow.inventory.internal.domain;

/** Why stock is moved between warehouses (docs 10 §3). Optional on a transfer order. */
public enum TransferReason { REBALANCING, DEMAND, CAMPAIGN, OVERFLOW, OTHER }
