package com.stockflow.assistant.internal.service;

import com.stockflow.assistant.internal.domain.ToolSpec;
import com.stockflow.catalog.api.CatalogMatch;
import com.stockflow.catalog.api.CatalogSearch;
import com.stockflow.common.security.Action;
import com.stockflow.common.security.RequiresPermission;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * U7: products by name or description, as the storefront search finds them.
 *
 * <p>{@code GET /api/v1/catalog/products} carries no permission — any signed-in user may search — so this tool carries
 * the assistant's own, the closest equivalent of "any signed-in back-office user". The results have no price and no
 * stock; the model combines them with {@code getStockLevels} for "is there any left".</p>
 *
 * <p><b>The first call when a product is named.</b> People ask about "the white 12 oz cup", the stock tools take a
 * SKU, and a SKU a model composes from the name is a guess that names nothing. So the description, like the system
 * prompt, opens with when to use this tool, and several plausible SKUs go back to the user to choose from instead of
 * one being picked for them.</p>
 *
 * <p>One product more than asked for is fetched, to tell a full page from the whole result: the storefront search
 * returns no total, and a list cut at the limit must say so (design §4.1).</p>
 */
@Component
class SearchCatalogTool implements AssistantTool {

    static final int MAX_ROWS = 10;

    private static final ToolSpec SPEC = new ToolSpec("searchCatalog",
            "Use it first whenever the user names a product in words instead of giving its SKU, then call the other "
                    + "tools with the SKU it returns; when several SKUs could be the one meant, list them and ask "
                    + "which. Searches the published product catalog by words (Vietnamese or English, accents "
                    + "optional, typos tolerated) or by SKU: product title, storefront slug, and its SKUs with names, "
                    + "best match first; truncated: true means more products matched than were returned. Only "
                    + "products published on the storefront are here: a SKU missing from the catalog may still be "
                    + "stocked, bought and sold, so never conclude from this tool that a SKU does not exist; when "
                    + "nothing fits, ask the user for the SKU. No prices and no stock: use getStockLevels with a SKU "
                    + "for stock.",
            ToolSchema.object()
                    .string("text", "What to search for, for example 'cốc giấy 12oz'")
                    .integer("limit", "How many products to return, 1 to " + MAX_ROWS)
                    .build());

    private final CatalogSearch catalog;

    SearchCatalogTool(CatalogSearch catalog) {
        this.catalog = catalog;
    }

    @Override
    public ToolSpec spec() {
        return SPEC;
    }

    @Override
    public String mirrors() {
        return null;
    }

    @Override
    @RequiresPermission(resource = ToolPermissions.ASSISTANT, action = Action.READ)
    public Answer run(ToolArguments arguments) {
        String text = arguments.text("text", 100);
        int limit = arguments.integer("limit", 1, MAX_ROWS);
        List<CatalogMatch> found = catalog.search(text, limit + 1);
        return new Answer(found.stream().limit(limit).map(Product::of).toList(), found.size() > limit);
    }

    /** @param truncated more products matched than {@code products} holds */
    record Answer(List<Product> products, boolean truncated) {
    }

    record Product(String title, String slug, List<Variant> skus) {

        static Product of(CatalogMatch match) {
            return new Product(match.title(), match.slug(),
                    match.variants().stream().map(v -> new Variant(v.sku(), v.name())).toList());
        }
    }

    record Variant(String sku, String name) {
    }
}
