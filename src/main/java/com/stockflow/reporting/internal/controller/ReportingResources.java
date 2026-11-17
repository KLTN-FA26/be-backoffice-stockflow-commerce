package com.stockflow.reporting.internal.controller;

/** The permission resources of this module's endpoints. */
final class ReportingResources {

    /** The sales dashboard (SCRUM-344); seeded by V20260903000100. */
    static final String REPORTS = "reporting-reports";
    /** Receivables per customer, aged by due date (2026-11-14 plan, BE2-B); seeded by V20261126000100. */
    static final String RECEIVABLES = "reporting-receivables";
    /** Stock in, out and on hand per SKU and warehouse (2026-11-14 plan, BE2-C); seeded by V20261126000100. */
    static final String STOCK_SUMMARY = "reporting-stock-summary";

    private ReportingResources() {
    }
}
