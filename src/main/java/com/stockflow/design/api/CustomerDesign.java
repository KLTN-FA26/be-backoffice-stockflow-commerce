package com.stockflow.design.api;

import java.time.Instant;
import java.util.UUID;

/**
 * A customer's design or design project as another module names it: whose it is, what it is called and where it
 * stands. What the chat needs to check that a conversation about it is its owner's (kltn-docs 16 BR-02) and to list the
 * customer's designs beside the conversation (16 §4.1 step 3) - never the document itself, which stays the design
 * module's (16 BR-05: design is not done through the chat).
 *
 * @param status    {@code DRAFT}, {@code PENDING_REVIEW}, {@code APPROVED} or {@code ARCHIVED} for a design;
 *                  {@code ACTIVE} or {@code ARCHIVED} for a project
 * @param changedAt when it last changed (or was created, when it never did)
 */
public record CustomerDesign(UUID id, UUID customerId, String name, String status, Instant changedAt) {
}
