package com.stockflow.common.cache;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Every cache in the system, its name and its time to live, declared in one place.
 *
 * <h2>Why the TTLs live here and not on the {@code @Cacheable}</h2>
 *
 * <p>Spring's {@code @Cacheable} has no TTL attribute — the expiry is configured on the cache
 * manager, by cache name. So the choice is between scattering names as string literals across
 * annotations and hoping they match a configuration block somewhere, or declaring the pair
 * together. The scattered version fails in a specific, nasty way: a typo in a
 * {@code @Cacheable("prodcuts")} creates a <b>new cache with the default TTL</b> rather than an
 * error, so the entry never expires the way anyone expects and nothing ever says so.</p>
 *
 * <p>Here the name is a constant (a typo will not compile) and the TTL sits beside it where the
 * trade-off is visible. {@link CacheConfig} builds the cache manager from {@link #ttls()}, so a
 * cache that is not in this map does not exist.</p>
 *
 * <h2>Choosing a TTL</h2>
 *
 * <p>The question is not "how long is the data valid" but "how long can a user look at a stale
 * value before it matters". Reference data that changes by hand a few times a year tolerates hours.
 * Anything a customer makes a decision on — a price, a stock figure — tolerates seconds, and only
 * because the alternative is hammering the database on every product view.</p>
 *
 * <p><b>Stock levels are deliberately absent from this list.</b> Caching available-to-promise is
 * how a system oversells: the cached figure says 5, three customers each check out 3, and the
 * warehouse discovers the problem at picking. ATP is read from the row under a lock at the moment
 * it matters, every time. If a cache is ever added for it, it must be for display only and must
 * never be what {@code reserve()} checks.</p>
 */
public final class CacheNames {

    /** Product master data: name, attributes, dimensions. Changes when someone edits a product. */
    public static final String PRODUCTS = "products";

    /** Storefront category tree. Rebuilt rarely, read on every page. */
    public static final String CATEGORIES = "categories";

    /** Effective price for a SKU. Short TTL: a stale price is a promise we may have to honour. */
    public static final String PRICES = "prices";

    /** The permission matrix, derived from annotations at startup. Effectively immutable. */
    public static final String PERMISSION_CATALOG = "permissionCatalog";

    /** A user's granted permissions and data scope, keyed by user id. */
    public static final String USER_PERMISSIONS = "userPermissions";

    /** Warehouse, zone and bin definitions. Reference data. */
    public static final String WAREHOUSE_LAYOUT = "warehouseLayout";

    /** Supplier records, read constantly by procurement screens. */
    public static final String SUPPLIERS = "suppliers";

    /** Rendered notification templates. Changes only on deployment. */
    public static final String NOTIFICATION_TEMPLATES = "notificationTemplates";

    private CacheNames() {
    }

    /**
     * The complete set of caches and how long entries live.
     *
     * <p>A {@link LinkedHashMap} so the startup log lists them in a stable order — useful when
     * comparing two environments.</p>
     */
    public static Map<String, Duration> ttls() {
        Map<String, Duration> ttls = new LinkedHashMap<>();
        ttls.put(PRODUCTS, Duration.ofMinutes(30));
        ttls.put(CATEGORIES, Duration.ofHours(6));
        // Short: a customer who is shown a stale price and adds to cart has been made a promise.
        ttls.put(PRICES, Duration.ofSeconds(60));
        ttls.put(PERMISSION_CATALOG, Duration.ofHours(12));
        // Short enough that revoking someone's access takes effect within a coffee break, long
        // enough that the identity module is not queried on every single request.
        ttls.put(USER_PERMISSIONS, Duration.ofMinutes(5));
        ttls.put(WAREHOUSE_LAYOUT, Duration.ofHours(2));
        ttls.put(SUPPLIERS, Duration.ofMinutes(30));
        ttls.put(NOTIFICATION_TEMPLATES, Duration.ofHours(12));
        return java.util.Collections.unmodifiableMap(ttls);
    }
}
