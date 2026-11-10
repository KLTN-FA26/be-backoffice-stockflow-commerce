package com.stockflow.fulfillment.internal.domain;

/**
 * Lifecycle of a task's check session, table {@code fulfillment.pack} (kltn-docs 08 §5.1; team plan 4C-6). Mapped
 * {@code EnumType.STRING}.
 *
 * <pre>
 *   PENDING ─started─▶ IN_PROGRESS ─completed in full─▶ COMPLETED
 *      │                 │  ▲ completed in part (a shipment made, the rest still to check)
 *      │                 └──┘
 *      └──────────── order cancelled before its first label ──▶ CANCELLED
 * </pre>
 *
 * <p>The repack station of before 4C-6 knew the same two steps as PACKING and PACKED; V20261113000700 renamed them to
 * the documented names, and that station, kept until team plan 4C-7, uses these. There is no hold for a design check:
 * printed goods arrive carrying the customer's design (kltn-docs 08 §1, V20261108000500).</p>
 */
public enum PackStatus { PENDING, IN_PROGRESS, COMPLETED, CANCELLED }
