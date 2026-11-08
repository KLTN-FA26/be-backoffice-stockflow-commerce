package com.stockflow.design.internal.domain;

/**
 * What lets someone read a customer's design that is not their own (kltn-docs 12 BR-03, B38; plan D7). The owner needs
 * none of it.
 *
 * @param reviewer holds the right to review designs: reads a customer's design while it waits for review
 * @param quotes   holds the right to read quote requests: reads a customer's design waiting for review or approved,
 *                 while a quote request on it is open - the sales staff answering that request, read-only
 */
public record DesignReadRights(boolean reviewer, boolean quotes) {

    /** Nothing beyond what an owner has. */
    public static final DesignReadRights NONE = new DesignReadRights(false, false);
}
