package com.stockflow.catalog.internal.service;

import com.stockflow.catalog.internal.repository.ListingRepository;
import com.stockflow.contracts.CatalogListingChanged;
import com.stockflow.contracts.ProductDiscontinued;
import com.stockflow.product.api.ProductPublication;
import com.stockflow.common.error.BusinessException;

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;

import java.util.UUID;

/** Durable dirty revision lives on the source row; failures do not roll back a publish decision. */
@Component
public class CatalogProjectionListener {
    private static final Logger LOG = LoggerFactory.getLogger(CatalogProjectionListener.class);
    private final CatalogProjectionService projection;
    private final ListingRepository listings;
    private final ProductPublication products;
    private UUID scanAfter;

    public CatalogProjectionListener(
            CatalogProjectionService projection, ListingRepository listings, ProductPublication products) {
        this.projection = projection;
        this.listings = listings;
        this.products = products;
    }

    @ApplicationModuleListener
    public void on(CatalogListingChanged event) {
        refresh(event.productId());
    }

    @ApplicationModuleListener
    public void on(ProductDiscontinued event) {
        refresh(event.productId());
    }

    /** Every restart repairs pre-existing publications; product locks serialize all writers. */
    @EventListener(ApplicationReadyEvent.class)
    public void rebuildPublished() {
        UUID after = null;
        while (true) {
            var ids = products.publishedPage(after, 50);
            if (ids.isEmpty()) return;
            for (var id : ids) refresh(id);
            after = ids.getLast();
        }
    }

    @Scheduled(fixedDelayString = "${stockflow.catalog.projection-retry-ms:30000}")
    @SchedulerLock(name = "catalogProjectionRetry", lockAtMostFor = "PT2M")
    public void retry() {
        // Oldest attempted first: fifty permanently failing products cannot starve later work.
        for (var id : listings.dirtyIds()) refresh(id);
        // Keyset traversal also finds missing snapshots. Failures cannot starve later products.
        var ids = products.publishedPage(scanAfter, 50);
        for (var id : ids) refresh(id);
        scanAfter = ids.size() < 50 ? null : ids.getLast();
    }

    private void refresh(UUID id) {
        try {
            projection.project(id);
        } catch (RuntimeException e) {
            projection.attempt(id);
            if (e instanceof BusinessException business)
                LOG.warn("Catalog projection remains pending for {}: {}", id, business.errorCode());
            else LOG.warn("Catalog projection remains pending for {}", id, e);
        }
    }
}
