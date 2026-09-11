package com.stockflow.identity.internal.controller;

import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST controller for the identity module (users, roles, permissions). STARTER STUB — no endpoints
 * yet. See {@code customer.internal.controller.CustomerController} for the endpoint checklist.
 * Note: this module underpins the platform's security; guard every future endpoint carefully.
 */
@RestController
@RequestMapping("/api/v1/identity")
class IdentityController {

    // TODO: inject IdentityService via the constructor and add guarded endpoints.
}
