package com.stockflow.product.internal.controller;

import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST controller for the product module. STARTER STUB — no endpoints yet.
 *
 * <p>When adding handlers follow {@code docs/adding-a-module.md} §4.5 and the checklist on
 * {@code customer.internal.controller.CustomerController}: permission-guard every public handler,
 * return {@code ApiResponse}/{@code Pages.toResponse}, never a JPA entity.</p>
 */
@RestController
@RequestMapping("/api/v1/products")
class ProductController {

    // TODO: inject ProductService via the constructor and add guarded endpoints.
}
