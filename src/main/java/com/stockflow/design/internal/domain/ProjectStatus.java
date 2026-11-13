package com.stockflow.design.internal.domain;

/**
 * Lifecycle of a design project (kltn-docs 12 §5.4). Mapped {@code EnumType.STRING}; {@code ck_design_project_status}
 * lists the same two.
 *
 * <ul>
 *   <li>{@code ACTIVE} - designs join and leave it, its library is used, batches and quotes are asked from it;</li>
 *   <li>{@code ARCHIVED} - closed by its owner once none of its designs waits for review (BR-22); final (B42). Its
 *       designs keep their status and are used on their own.</li>
 * </ul>
 */
public enum ProjectStatus { ACTIVE, ARCHIVED }
