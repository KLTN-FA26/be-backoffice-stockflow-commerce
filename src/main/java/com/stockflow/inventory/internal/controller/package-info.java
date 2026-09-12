/**
 * REST controllers and the mappers between DTO and application types. Request and response DTOs
 * themselves live in the {@code dto} subpackage, not loose in here — see its own package-info.
 *
 * <p>Named {@code controller} for the layer everyone recognises. Its job is narrow: translate HTTP
 * into a call on this module's application service and translate the answer back. No business rule
 * belongs here — if a controller has an {@code if} about the domain, that rule is in the wrong
 * place and cannot be unit-tested.</p>
 *
 * <p>Every handler method must be {@code public}: {@code @RequiresPermission} is applied by a
 * Spring proxy, and a proxy cannot intercept a non-public method — the annotation would be ignored
 * silently and the endpoint would run with no authorisation at all.</p>
 */
package com.stockflow.inventory.internal.controller;
