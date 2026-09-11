package com.stockflow.reporting.internal.service;

import com.stockflow.reporting.api.ReportingService;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The only implementation of {@link ReportingService}.
 *
 * <p>STARTER STUB. Package-private class, public interface: Spring injects it by the interface, so
 * nobody can bypass the port by autowiring the concrete class.</p>
 *
 * <p>Reporting reads and does not write, so its query methods should be
 * {@code @Transactional(readOnly = true)}. Fill in: constructor-inject a read-only projection
 * repository — never a field ({@code ArchitectureTest.noFieldInjection}). Keep every advised method
 * {@code public}, or the proxy is silently skipped ({@code verify.py} #14). Because a read model
 * crosses no schema, prefer a projection over reaching into another module's tables.</p>
 */
@Service
@Transactional(readOnly = true)
class ReportingServiceImpl implements ReportingService {

    // TODO: constructor-inject a projection repository here, then implement ReportingService.
}
