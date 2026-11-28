package com.stockflow.customer.internal.domain;

/** Where a lead stands (sales-owner plan §10.7; {@code ck_lead_status}). */
public enum LeadStatus {
    NEW, ASSIGNED, CONTACTED, CONVERTED, DISQUALIFIED
}
