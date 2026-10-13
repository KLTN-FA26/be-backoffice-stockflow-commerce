/**
 * <b>Stock consumption: the one door through which picked goods leave the books.</b>
 *
 * <p>A second named interface of {@code inventory}, next to {@code api}, and deliberately narrower.
 * Deducting stock and putting picked goods back are not things any module may do: exactly one caller,
 * {@code fulfillment}, is entitled to say "these units have left the shelf" (or "are back on it").
 * Putting {@link com.stockflow.inventory.consumption.PickConsumption} on {@code inventory :: api}
 * would hand that power to every module that may read availability. As its own named interface it is
 * reachable only by a module that lists {@code "inventory :: consumption"} in its
 * {@code allowedDependencies} — today fulfillment alone — and {@code ModularityTest} refuses anyone
 * else.</p>
 *
 * <p>In-process rather than over HTTP (decision G4 §6 #7, SCRUM-275): the deduction joins the pick
 * line's transaction, so the line cannot say PICKED while the stock was never deducted, or the other
 * way round. The earlier design called the HTTP {@code consumption} endpoint from fulfillment; that
 * endpoint stays for operations and runs the same implementation.</p>
 *
 * <p>The same rules as {@code api} apply: records and interfaces only, nothing from {@code internal}.</p>
 */
@org.springframework.modulith.NamedInterface("consumption")
package com.stockflow.inventory.consumption;
