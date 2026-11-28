package com.stockflow.catalog.internal.service;

import com.stockflow.catalog.api.CatalogMatch;
import com.stockflow.catalog.api.CatalogSearch;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/** {@link CatalogSearch} over {@link CatalogSearchService}: one search, ranked by relevance, first page only. */
@Service
@Transactional(readOnly = true)
class CatalogSearchQueries implements CatalogSearch {

    private final CatalogSearchService search;

    CatalogSearchQueries(CatalogSearchService search) {
        this.search = search;
    }

    @Override
    public List<CatalogMatch> search(String text, int limit) {
        if (text == null || text.isBlank()) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "text is required");
        }
        if (limit < 1 || limit > MAX_RESULTS) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "limit must be between 1 and " + MAX_RESULTS);
        }
        return search.search(text.strip(), "relevance", 0, limit).items().stream()
                .map(card -> new CatalogMatch(card.title(), card.slug(), card.skus().stream()
                        .map(sku -> new CatalogMatch.Variant(sku.sku(), sku.name())).toList()))
                .toList();
    }
}
