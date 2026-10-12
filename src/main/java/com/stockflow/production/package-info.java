/**
 * <b>Print production: sample requests and production orders (LSX)</b>
 *
 * <p>WBS 3.25 · database schema {@code production} · plan
 * {@code docs/business-design/2026-10-06-b2b-production-plan.md}</p>
 *
 * <p><b>May depend on:</b> customer (who a sample is for, and which customer a portal user is),
 * design (the confirmed snapshot a sample prints), warehouse (the workshop a sample is made in),
 * inventory (whether the workshop holds the blanks a sample needs) and order (the released order a
 * production order is created for). Plus {@code common} and
 * {@code contracts}, which are available to every module.</p>
 *
 * <p>Nothing depends on this module: the order module learns what production did from
 * {@code SampleApproved} and {@code ProductionCompleted} and keeps its own record, so {@code order} never
 * imports {@code production} and the two cannot form a cycle.</p>
 *
 * <p>The module has two packages and the split is the whole point:</p>
 * <ul>
 *   <li>{@code api} — the public API, exposed as a named interface. Empty today: no other module calls
 *       production.</li>
 *   <li>{@code internal} — everything else, invisible to other modules. The compiler will not stop
 *       you reaching in; {@code ModularityTest} will.</li>
 * </ul>
 */
@org.springframework.modulith.ApplicationModule(allowedDependencies = {"customer :: api", "design :: api",
        "warehouse :: api", "inventory :: api", "order :: api"})
package com.stockflow.production;
