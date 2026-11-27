package com.stockflow.fulfillment.api;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

/** Read-only load codes currently being loaded for orders whose payment may be reversed. */
public interface LoadingLoadLookup {
    List<String> loadingLoadCodes(Collection<UUID> orderIds);
}
