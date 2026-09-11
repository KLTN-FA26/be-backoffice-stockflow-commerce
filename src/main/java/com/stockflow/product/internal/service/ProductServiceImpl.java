package com.stockflow.product.internal.service;

import com.stockflow.product.api.ProductService;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The only implementation of {@link ProductService}, and the module's transaction boundary.
 *
 * <p>STARTER STUB. Package-private class, public interface: Spring injects it by the interface, so
 * nobody can bypass the port by autowiring the concrete class. The application layer orchestrates —
 * load the aggregate, call its method, save, publish — and holds no business rule of its own.</p>
 *
 * <p>Fill in: constructor-inject the repository port (and {@code Clock} if the module deals with
 * time) — never a field ({@code ArchitectureTest.noFieldInjection}). Keep every advised method
 * {@code public}, or the {@code @Transactional} proxy is silently skipped ({@code verify.py} #14).
 * See {@code inventory.internal.service.InventoryServiceImpl}.</p>
 */
@Service
@Transactional
class ProductServiceImpl implements ProductService {

    // TODO: constructor-inject the repository port here, then implement ProductService.
}
