/**
 * Application services: the use cases this module offers, one public method per use case.
 *
 * <p>This is the transaction boundary. A service method orchestrates — load the aggregate, call a
 * behaviour on it, save, publish an event — and holds no business rules of its own; those live on
 * the aggregate in {@code internal.domain}, where they can be tested without a container.</p>
 *
 * <p>Event listeners for other modules' events belong here as well: reacting to something that
 * happened elsewhere is a use case like any other.</p>
 */
package com.stockflow.notification.internal.service;
