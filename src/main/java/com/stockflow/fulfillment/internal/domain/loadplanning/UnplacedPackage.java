package com.stockflow.fulfillment.internal.domain.loadplanning;

/**
 * A carton left out of the plan: one entry per carton, by the {@code ref} it came in with, and its {@code sourceRef}
 * so a caller can count the cartons of an estimate line that did not fit.
 */
public record UnplacedPackage(String ref, String sourceRef, UnplacedReason reason) { }
