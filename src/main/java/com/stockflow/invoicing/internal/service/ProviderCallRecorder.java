package com.stockflow.invoicing.internal.service;

import com.stockflow.invoicing.internal.domain.ProviderCallLog;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Writes one call to the provider into the log, in a transaction of its own: the call happened whether or not what it
 * was made for commits, and a provider that does not answer is exactly the call worth having on record. A separate bean
 * because a method calling its own {@code @Transactional} method bypasses the proxy.
 */
@Component
class ProviderCallRecorder {

    private final ProviderCallLog log;

    ProviderCallRecorder(ProviderCallLog log) {
        this.log = log;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(ProviderCallLog.Call call) {
        log.record(call);
    }
}
