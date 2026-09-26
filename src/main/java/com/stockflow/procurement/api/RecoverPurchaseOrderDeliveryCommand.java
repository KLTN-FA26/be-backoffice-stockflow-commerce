package com.stockflow.procurement.api;

/** Recovery requires reconciliation of uncertain transport outcomes and the original promised date. */
public record RecoverPurchaseOrderDeliveryCommand(String reason, boolean reconciled, boolean acknowledgePastDue) {}
