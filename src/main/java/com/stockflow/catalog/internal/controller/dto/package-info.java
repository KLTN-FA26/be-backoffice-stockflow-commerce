/**
 * Request and response DTOs for this module's REST controllers, and nothing else.
 *
 * <p>A separate package from {@code internal.controller} so the controller directory does not turn
 * into an unsorted mix of controllers, DTOs and mappers as endpoints accumulate. A type here is
 * named for its wire role ({@code <Verb><Noun>Request}, {@code <Noun>Response}, see
 * {@code docs/adding-a-module.md} §2) and is mapped to and from this module's {@code api} types by
 * the module's {@code <Module>WebMapper} — never returned or accepted by a controller directly.</p>
 */
package com.stockflow.catalog.internal.controller.dto;
