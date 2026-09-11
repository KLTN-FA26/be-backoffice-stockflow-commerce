package com.stockflow.notification.internal.service;

import com.stockflow.notification.api.NotificationService;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The only implementation of {@link NotificationService}.
 *
 * <p>STARTER STUB, and optional — see {@link NotificationService}. The real work today is done by
 * {@code OrderNotificationListener} (reacting to events after commit) and {@code NotificationSender}
 * (the outbound channel); both stay as they are. Keep this class only if a synchronous api is
 * needed, and delete it together with the interface otherwise.</p>
 *
 * <p>If kept: package-private class, public interface; constructor injection only
 * ({@code ArchitectureTest.noFieldInjection}); advised methods {@code public} ({@code verify.py}
 * #14).</p>
 */
@Service
@Transactional
class NotificationServiceImpl implements NotificationService {

    // TODO: keep only if a synchronous trigger is needed; otherwise delete with NotificationService.
}
