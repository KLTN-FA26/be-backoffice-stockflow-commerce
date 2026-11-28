package com.stockflow.payment.api;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

/** Read-only load codes awaiting handover for orders whose payment may be reversed. */
public interface LoadingLoadLookup {
    List<String> loadingLoadCodes(Collection<UUID> orderIds);
}
