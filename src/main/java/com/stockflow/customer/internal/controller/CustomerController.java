package com.stockflow.customer.internal.controller;

import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST controller for the customer module. STARTER STUB — no endpoints yet.
 *
 * <p>When you add a handler, follow {@code docs/adding-a-module.md} §4.5:</p>
 * <ul>
 *   <li>declare a {@code @PermissionResource} on this class and guard every handler with a
 *       {@code @RequiresPermission} naming the resource, action and scope — an unguarded endpoint
 *       has no authorisation at all, and {@code PermissionCatalogValidator} fails startup if a
 *       guard names a resource no {@code @PermissionResource} declares;</li>
 *   <li>every handler method must be {@code public}, or the permission proxy is skipped silently;</li>
 *   <li>return {@code ApiResponse<...>} / {@code Pages.toResponse(...)}, never a JPA entity;</li>
 *   <li>inject {@code CustomerService} through the constructor and map DTO ⇄ api types with a
 *       {@code CustomerWebMapper} (see {@code inventory.internal.controller}).</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/v1/customers")
class CustomerController {

    // TODO: inject CustomerService via the constructor and add guarded endpoints (see class javadoc).
}
