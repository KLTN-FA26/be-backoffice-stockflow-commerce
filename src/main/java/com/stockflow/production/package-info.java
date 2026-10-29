/**
 * <b>Print production: sample requests and production orders (LSX)</b>
 *
 * <p>WBS 3.25 · database schema {@code production} · plan
 * {@code docs/business-design/2026-10-06-b2b-production-plan.md}</p>
 *
 * <p><b>May depend on:</b> customer (who a sample is for, and which customer a portal user is),
 * design (the confirmed snapshot a sample prints), warehouse (the workshop a sample is made in),
 * inventory (whether the workshop holds the blanks a sample needs), order (the released order a
 * production order is created for), procurement (subcontracted work) and product (whether a blank is still
 * ACTIVE before a new sample takes it). Plus {@code common} and
 * {@code contracts}, which are available to every module.</p>
 *
 * <p>Only the back-office assistant depends on this module, through {@code api}, to read production orders
 * (ADR-0010); nothing depends on the assistant, so that cannot form a cycle. The order module learns what
 * production did from {@code SampleApproved} and {@code ProductionCompleted} and keeps its own record, so
 * {@code order} never imports {@code production} and the two cannot form a cycle either.</p>
 *
 * <p>The module has two packages and the split is the whole point:</p>
 * <ul>
 *   <li>{@code api} — the public API, exposed as a named interface: {@code ProductionOrderQueries}, production
 *       orders by number, by sales order or by status, under the rules of the production orders screen.</li>
 *   <li>{@code internal} — everything else, invisible to other modules. The compiler will not stop
 *       you reaching in; {@code ModularityTest} will.</li>
 * </ul>
 */
@org.springframework.modulith.ApplicationModule(allowedDependencies = {"customer :: api", "design :: api",
        "warehouse :: api", "inventory :: api", "order :: api", "procurement :: api", "product :: api"})
package com.stockflow.production;
