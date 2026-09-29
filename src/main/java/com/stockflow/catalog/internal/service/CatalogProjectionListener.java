package com.stockflow.catalog.internal.service;

import com.stockflow.catalog.internal.repository.ListingRepository;
import com.stockflow.contracts.CatalogListingChanged;
import com.stockflow.contracts.ProductDiscontinued;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Durable dirty revision lives on the source row; failures do not roll back a publish decision. */
@Component
public class CatalogProjectionListener {
    private static final org.slf4j.Logger LOG=org.slf4j.LoggerFactory.getLogger(CatalogProjectionListener.class);
    private final CatalogProjectionService projection;
    private final ListingRepository listings;
    public CatalogProjectionListener(CatalogProjectionService projection,ListingRepository listings) {
        this.projection=projection; this.listings=listings;
    }
    @ApplicationModuleListener
    public void on(CatalogListingChanged event) { refresh(event.productId()); }
    @ApplicationModuleListener
    public void on(ProductDiscontinued event) { refresh(event.productId()); }
    @Scheduled(fixedDelayString="${stockflow.catalog.projection-retry-ms:30000}")
    @SchedulerLock(name="catalogProjectionRetry",lockAtMostFor="PT2M")
    public void retry() {
        // Oldest attempted first: fifty permanently failing products cannot starve later work.
        for (var id:listings.dirtyIds()) refresh(id);
    }
    private void refresh(java.util.UUID id) {
        try { projection.attempt(id); projection.project(id); }
        catch (RuntimeException e) { LOG.warn("Catalog projection remains pending for {}",id,e); }
    }
}
