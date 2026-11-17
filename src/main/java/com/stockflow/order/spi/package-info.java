/**
 * <b>What the {@code order} module needs from the modules above it</b> - a service-provider interface, the reverse of
 * {@code api}.
 *
 * <p>Closing the rest of a partly fulfilled order (kltn-docs 17 §4.8 step 33; owner decision WF4-CLOSE-REST,
 * 2026-11-13) must leave the cartons already checked and labelled for it - waiting at dispatch for their load, not handed
 * over yet - on the order: they are still delivered. Which cartons those are is fulfillment's knowledge, and fulfillment
 * already depends on {@code order :: api}; a dependency the other way would be a cycle. So the order declares here the
 * one question it needs answered, fulfillment implements it ({@code allowedDependencies} names {@code "order :: spi"}),
 * and the closure asks it synchronously, inside its own transaction under the order's lock.</p>
 *
 * <p>Like {@code api}: records and interfaces only, nothing internal.</p>
 */
@org.springframework.modulith.NamedInterface("spi")
package com.stockflow.order.spi;
