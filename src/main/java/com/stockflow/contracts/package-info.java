/**
 * <b>Cross-module event contracts.</b>
 *
 * <p>An event published by one module and consumed by another lives here, not in the publisher.
 * The reason is cycles: if {@code order} listened to {@code payment}'s event type and
 * {@code payment} listened to {@code order}'s, the two modules would depend on each other and
 * {@code ModularityTest} would reject the build. A neutral module both may depend on breaks that.</p>
 *
 * <p><b>Rules:</b> flat records and primitives only, no imports from any module. Once published,
 * you may only add nullable fields — removing one or changing a type breaks every listener.</p>
 */
@org.springframework.modulith.ApplicationModule(type = org.springframework.modulith.ApplicationModule.Type.OPEN)
package com.stockflow.contracts;
