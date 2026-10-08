package com.stockflow;

import org.springframework.test.context.TestPropertySource;

/** The same authenticated catalog regression, starting with a blank database and actual demo seeds. */
@TestPropertySource(properties = "stockflow.test.catalog-upgrade=false")
class CatalogProjectionFreshStartupIntegrationTest extends CatalogProjectionStartupIntegrationTest {
}
