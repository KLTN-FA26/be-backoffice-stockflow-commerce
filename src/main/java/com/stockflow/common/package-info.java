/**
 * <b>Shared kernel</b> — types every module is allowed to use.
 *
 * <p>Declared an OPEN module, which is what lets other modules reach into its nested packages.
 * That freedom is the exact reason it must stay small: anything that lands here can never be
 * moved without touching all fourteen modules.</p>
 *
 * <p><b>What belongs here:</b> base types with no business meaning of their own — AggregateRoot,
 * Money, the error model, the API envelope, auditing, security.</p>
 *
 * <p><b>What does not:</b> anything a single module owns. If only {@code inventory} needs it, it
 * lives in {@code inventory}. A shared kernel that grows becomes a distributed monolith's worth of
 * coupling inside one process.</p>
 */
@org.springframework.modulith.ApplicationModule(type = org.springframework.modulith.ApplicationModule.Type.OPEN)
package com.stockflow.common;
