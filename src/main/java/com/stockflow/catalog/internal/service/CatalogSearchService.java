package com.stockflow.catalog.internal.service;

import com.stockflow.catalog.internal.domain.SearchQuery;
import com.stockflow.catalog.internal.domain.SearchQueryNormalizer;
import com.stockflow.catalog.internal.repository.CatalogSearchRepository;
import com.stockflow.catalog.internal.repository.CatalogSearchRepository.Candidate;
import com.stockflow.catalog.internal.repository.CatalogSearchRepository.Weights;
import com.stockflow.common.api.FieldError;
import com.stockflow.common.api.PageResponse;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.logging.LogSafe;
import com.stockflow.common.persistence.Pages;
import com.stockflow.inventory.api.InventoryService;
import com.stockflow.product.api.CategoryNavigation;
import com.stockflow.product.api.ProductPublication;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Search and listing of the published catalog (docs 13 §4.2, SCRUM-170/181/182).
 *
 * <p><b>Visibility.</b> Only a published product is ever returned, decided twice: the repository
 * filters in SQL before anything is cut (so a page is full and the total is true), and the product
 * module is asked once more for the candidates it kept, because the catalog snapshot can lag a
 * discontinuation by the time an event takes to arrive. Nothing here returns a price or a stock
 * figure; stock only nudges the order.</p>
 *
 * <p><b>Ranking window.</b> Stock lives in another module's schema, and a SQL join across schemas is
 * not allowed, so the best {@code rankingWindow} text matches are fetched, re-ranked in memory with
 * the in-stock bonus, and paged from there. The total is the size of that window. A catalogue
 * larger than the window is paged only through its best matches, which for a search box is what a
 * shopper reads anyway.</p>
 *
 * <p><b>Privacy.</b> The query is free text typed by a customer and may carry a name or a phone number:
 * it is never logged, and error messages never repeat it.</p>
 */
@Service
public class CatalogSearchService {

    private static final Logger LOG = LoggerFactory.getLogger(CatalogSearchService.class);

    /** Suggestions need a couple of letters before they mean anything (docs 13 gives no number). */
    public static final int MIN_SUGGEST_LENGTH = 2;
    public static final int MAX_SUGGESTIONS = 10;
    public static final int DEFAULT_SUGGESTIONS = 5;

    /** {@code price} is not here: sorting by price waits for SCRUM-428 (docs 13 §4.2 step 2). */
    public enum Sort {
        RELEVANCE, NEWEST, TITLE;

        static Sort parse(String value) {
            if (value == null || value.isBlank()) return null;
            try {
                return valueOf(value.strip().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                throw new BusinessException(ErrorCode.UNSUPPORTED_PARAMETER,
                        "Sort must be one of relevance, newest, title",
                        List.of(new FieldError("sort", "must be one of relevance, newest, title", null)));
            }
        }
    }

    public record Suggestion(String title, String slug, String sku) {}

    private final CatalogSearchRepository repository;
    private final ProductPublication products;
    private final InventoryService inventory;
    private final SearchProperties properties;
    private final CategoryNavigation categories;

    public CatalogSearchService(
            CatalogSearchRepository repository,
            ProductPublication products,
            InventoryService inventory,
            SearchProperties properties,
            CategoryNavigation categories) {
        this.categories = categories;
        this.repository = repository;
        this.products = products;
        this.inventory = inventory;
        this.properties = properties;
    }

    /**
     * @param q    search text; null/blank lists the whole catalogue
     * @param sort {@code relevance} (default with text), {@code newest} (default without), {@code title};
     *             {@code relevance} without text means {@code newest}
     * @throws BusinessException {@code VALIDATION_FAILED} for text over 100 characters;
     *                           {@code UNSUPPORTED_PARAMETER} for a bad page, size or sort
     */
    @Transactional(readOnly = true)
    public PageResponse<CatalogCommerceService.Card> search(String q, String sort, int page, int size) {
        return search(q, sort, page, size, null);
    }

    /**
     * {@link #search(String, String, int, int)} inside one category of the navigation and every category under it
     * (SCRUM-171), decided in the database query itself, so the page and the total are those of the category.
     *
     * @param category a category slug; null or blank for the whole catalogue
     * @throws BusinessException {@code CATEGORY_NOT_FOUND} for a slug that is unknown, or whose category or an
     *                           ancestor is inactive — the navigation does not show it either
     */
    @Transactional(readOnly = true)
    public PageResponse<CatalogCommerceService.Card> search(String q, String sort, int page, int size,
                                                            String category) {
        var paging = Pages.of(page, size);
        Set<java.util.UUID> only = null;
        if (category != null && !category.isBlank()) {
            Set<java.util.UUID> subtree = categories.visibleSubtree(category).orElseThrow(() ->
                    new BusinessException(ErrorCode.CATEGORY_NOT_FOUND, "No category " + category.strip()));
            only = categories.productsIn(subtree);
            if (only.isEmpty()) {
                return PageResponse.empty(page, size);
            }
        }
        Sort requested = Sort.parse(sort);
        SearchQuery query = SearchQueryNormalizer.normalize(q);
        boolean hasText = q != null && !q.isBlank();
        if (hasText && query.isEmpty()) {
            return PageResponse.empty(page, size); // only punctuation: nothing can match
        }
        Sort effective = requested != null ? requested : (hasText ? Sort.RELEVANCE : Sort.NEWEST);
        if (!hasText && effective == Sort.RELEVANCE) effective = Sort.NEWEST;

        int window = properties.rankingWindow();
        Weights weights = weights();
        List<Candidate> found;
        if (!hasText) {
            var order = effective == Sort.TITLE ? CatalogSearchRepository.Order.TITLE
                    : CatalogSearchRepository.Order.NEWEST;
            found = only == null ? repository.listAll(order, window) : repository.listAll(order, window, only);
        } else {
            found = only == null ? repository.match(query, weights, window)
                    : repository.match(query, weights, window, only);
            if (found.isEmpty()) {
                found = only == null ? repository.similar(query, weights, properties.fallbackSimilarity(), window)
                        : repository.similar(query, weights, properties.fallbackSimilarity(), window, only);
            }
        }
        List<Candidate> visible = liveVisible(found);
        List<Candidate> ordered = switch (effective) {
            case RELEVANCE -> byRelevance(visible);
            case NEWEST -> hasText ? visible.stream().sorted(NEWEST_FIRST).toList() : visible;
            case TITLE -> hasText ? visible.stream().sorted(TITLE_FIRST).toList() : visible;
        };
        LOG.debug("Catalog search sort={} candidates={} visible={}",
                LogSafe.text(effective), found.size(), visible.size());

        int from = (int) Math.min(paging.getOffset(), ordered.size());
        int to = Math.min(from + paging.getPageSize(), ordered.size());
        return PageResponse.of(
                ordered.subList(from, to).stream().map(CatalogSearchService::card).toList(),
                page, size, ordered.size());
    }

    /**
     * Title completions for the search box: at most {@code limit} products, best text match first.
     *
     * @throws BusinessException {@code VALIDATION_FAILED} (field {@code q}) for fewer than 2 letters or
     *                           digits, or over 100 characters; {@code UNSUPPORTED_PARAMETER} for a
     *                           limit outside 1..10
     */
    @Transactional(readOnly = true)
    public List<Suggestion> suggest(String q, int limit) {
        if (limit < 1 || limit > MAX_SUGGESTIONS) {
            throw new BusinessException(ErrorCode.UNSUPPORTED_PARAMETER,
                    "Limit must be between 1 and " + MAX_SUGGESTIONS,
                    List.of(new FieldError("limit", "must be between 1 and " + MAX_SUGGESTIONS, null)));
        }
        SearchQuery query = SearchQueryNormalizer.normalize(q);
        if (query.text().codePointCount(0, query.text().length()) < MIN_SUGGEST_LENGTH) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "Type at least " + MIN_SUGGEST_LENGTH + " characters",
                    List.of(new FieldError("q", "must have at least " + MIN_SUGGEST_LENGTH
                            + " letters or digits", null)));
        }
        // A few extra: some may be dropped by the live visibility check below.
        List<Candidate> visible = liveVisible(repository.suggest(query, weights(), limit * 3));
        return visible.stream().limit(limit)
                .map(c -> new Suggestion(c.title(), c.slug(),
                        c.matchedSku() != null ? c.matchedSku() : c.skus().getFirst()))
                .toList();
    }

    // ---- ordering -------------------------------------------------------------------------

    private static final Comparator<Candidate> NEWEST_FIRST = Comparator
            .comparing(Candidate::listedAt, Comparator.nullsLast(Comparator.<Instant>reverseOrder()))
            .thenComparing(Candidate::productId, Comparator.reverseOrder());

    private static final Comparator<Candidate> TITLE_FIRST = Comparator
            .comparing((Candidate c) -> SearchQueryNormalizer.fold(c.title()))
            .thenComparing(Candidate::productId);

    /** Text score plus the in-stock bonus; ties go to the alphabetically first title, then the id. */
    private List<Candidate> byRelevance(List<Candidate> candidates) {
        if (candidates.isEmpty()) return candidates;
        Set<String> skus = candidates.stream().flatMap(c -> c.skus().stream()).collect(Collectors.toSet());
        Map<String, Long> available = inventory.availableQuantities(skus);
        double bonus = properties.inStockWeight();
        record Scored(Candidate candidate, double score) {}
        return candidates.stream()
                .map(c -> new Scored(c, c.textScore()
                        + (c.skus().stream().anyMatch(s -> available.getOrDefault(s, 0L) > 0) ? bonus : 0)))
                .sorted(Comparator.comparingDouble(Scored::score).reversed()
                        .thenComparing(s -> SearchQueryNormalizer.fold(s.candidate().title()))
                        .thenComparing(s -> s.candidate().productId()))
                .map(Scored::candidate)
                .toList();
    }

    private List<Candidate> liveVisible(List<Candidate> candidates) {
        if (candidates.isEmpty()) return candidates;
        Set<java.util.UUID> published = products.published(
                candidates.stream().map(Candidate::productId).collect(Collectors.toCollection(HashSet::new)));
        return candidates.stream().filter(c -> published.contains(c.productId())).toList();
    }

    private Weights weights() {
        return new Weights(properties.textRankWeight(), properties.similarityWeight(), properties.exactSkuWeight());
    }

    private static CatalogCommerceService.Card card(Candidate c) {
        return new CatalogCommerceService.Card(c.productId(), c.slug(), c.title(), c.seoTitle());
    }
}
