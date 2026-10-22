package com.stockflow.catalog.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.stockflow.catalog.internal.domain.SearchQuery;
import com.stockflow.catalog.internal.repository.CatalogSearchRepository;
import com.stockflow.catalog.internal.repository.CatalogSearchRepository.Candidate;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.inventory.api.InventoryService;
import com.stockflow.product.api.ProductPublication;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.IntStream;

class CatalogSearchServiceTest {

    private final CatalogSearchRepository repository = mock(CatalogSearchRepository.class);
    private final ProductPublication products = mock(ProductPublication.class);
    private final InventoryService inventory = mock(InventoryService.class);
    private final SearchProperties properties = new SearchProperties(1.0, 1.0, 10.0, 0.25, 0.2, 500);
    private final CatalogSearchService service =
            new CatalogSearchService(repository, products, inventory, properties,
                    mock(com.stockflow.product.api.CategoryNavigation.class));

    private static Candidate candidate(String title, double score, String sku, Instant listedAt) {
        return new Candidate(UUID.randomUUID(), title.toLowerCase().replace(' ', '-'), title, null, score,
                List.of(sku), null, listedAt);
    }

    /** Unless a test says otherwise, every product is still sellable when its card is read: one SKU each. */
    @org.junit.jupiter.api.BeforeEach
    void everyProductIsSellable() {
        // every product is still sellable when its card is read: one SKU each
        when(products.cards(any())).thenAnswer(i -> {
            Set<UUID> ids = i.getArgument(0);
            var map = new java.util.HashMap<UUID, com.stockflow.product.api.PublishedProductCard>();
            ids.forEach(id -> map.put(id, new com.stockflow.product.api.PublishedProductCard(null,
                    List.of(new com.stockflow.product.api.PublishedProductCard.Sku("S-" + id, "Name")))));
            return map;
        });
    }

    private void everythingPublished(Candidate... all) {
        when(products.published(any())).thenAnswer(i -> i.getArgument(0));
    }

    // ---- relevance ------------------------------------------------------------------------

    @Test
    void inStockProductWinsAnExactTieOnTextScore() {
        var out = candidate("Ly nhua A", 0.5, "A-1", null);
        var in = candidate("Ly nhua B", 0.5, "B-1", null);
        when(repository.match(any(), any(), anyInt())).thenReturn(List.of(out, in));
        everythingPublished();
        when(inventory.availableQuantities(any())).thenReturn(Map.of("A-1", 0L, "B-1", 7L));

        var page = service.search("ly nhua", null, 0, 20);

        assertThat(page.items()).extracting(c -> c.title()).containsExactly("Ly nhua B", "Ly nhua A");
    }

    @Test
    void stockIsABonusNotAnOverride() {
        var better = candidate("Better text", 0.9, "A-1", null);
        var stocked = candidate("Worse text", 0.5, "B-1", null);
        when(repository.match(any(), any(), anyInt())).thenReturn(List.of(better, stocked));
        everythingPublished();
        when(inventory.availableQuantities(any())).thenReturn(Map.of("B-1", 100L));

        // 0.9 vs 0.5 + 0.25: text still wins; the bonus is a tie-breaker between close matches.
        assertThat(service.search("text", "relevance", 0, 20).items())
                .extracting(c -> c.title()).containsExactly("Better text", "Worse text");
    }

    @Test
    void stockBonusFollowsTheConfiguredWeight() {
        var service = new CatalogSearchService(repository, products, inventory,
                new SearchProperties(1.0, 1.0, 10.0, 1.0, 0.2, 500),
                mock(com.stockflow.product.api.CategoryNavigation.class));
        var better = candidate("Better text", 0.9, "A-1", null);
        var stocked = candidate("Worse text", 0.5, "B-1", null);
        when(repository.match(any(), any(), anyInt())).thenReturn(List.of(better, stocked));
        everythingPublished();
        when(inventory.availableQuantities(any())).thenReturn(Map.of("B-1", 1L));

        assertThat(service.search("text", null, 0, 20).items())
                .extracting(c -> c.title()).containsExactly("Worse text", "Better text");
    }

    @Test
    void equalScoresFallBackToFoldedTitleThenId() {
        var b = candidate("Bàn", 0.5, "B-1", null);
        var a = candidate("Ao", 0.5, "A-1", null);
        when(repository.match(any(), any(), anyInt())).thenReturn(List.of(b, a));
        everythingPublished();
        when(inventory.availableQuantities(any())).thenReturn(Map.of());

        assertThat(service.search("x", null, 0, 20).items()).extracting(c -> c.title()).containsExactly("Ao", "Bàn");
    }

    // ---- visibility -----------------------------------------------------------------------

    @Test
    void productTheProductModuleNoLongerPublishesIsDroppedAndNotCounted() {
        var kept = candidate("Kept", 0.5, "K-1", null);
        var gone = candidate("Discontinued meanwhile", 0.9, "G-1", null);
        when(repository.match(any(), any(), anyInt())).thenReturn(List.of(gone, kept));
        when(products.published(any())).thenReturn(Set.of(kept.productId()));
        when(inventory.availableQuantities(any())).thenReturn(Map.of());

        var page = service.search("kept", null, 0, 20);

        assertThat(page.items()).extracting(c -> c.title()).containsExactly("Kept");
        assertThat(page.totalElements()).isEqualTo(1);
    }

    // ---- paging ---------------------------------------------------------------------------

    @Test
    void pagesAreSlicesOfTheRankedListAndTheTotalCountsEverything() {
        var all = IntStream.range(0, 7)
                .mapToObj(i -> candidate("P" + i, 1.0 - i / 10.0, "S-" + i, null)).toList();
        when(repository.match(any(), any(), anyInt())).thenReturn(all);
        everythingPublished();
        when(inventory.availableQuantities(any())).thenReturn(Map.of());

        var last = service.search("p", null, 2, 3);
        assertThat(last.items()).extracting(c -> c.title()).containsExactly("P6");
        assertThat(last.totalElements()).isEqualTo(7);
        assertThat(last.totalPages()).isEqualTo(3);
        assertThat(last.hasNext()).isFalse();
        assertThat(service.search("p", null, 5, 3).items()).isEmpty();
    }

    @Test
    void badPagingIsRefusedBeforeAnyQueryRuns() {
        for (int[] bad : new int[][] {{-1, 20}, {0, 0}, {0, 201}}) {
            assertThatThrownBy(() -> service.search("ly", null, bad[0], bad[1]))
                    .isInstanceOfSatisfying(BusinessException.class,
                            e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.UNSUPPORTED_PARAMETER));
        }
        verifyNoInteractions(repository, products, inventory);
    }

    // ---- sort -----------------------------------------------------------------------------

    @Test
    void priceAndAnyOtherSortIsRefused() {
        for (String sort : List.of("price", "price,asc", "popular", "rank")) {
            assertThatThrownBy(() -> service.search("ly", sort, 0, 20))
                    .isInstanceOfSatisfying(BusinessException.class, e -> {
                        assertThat(e.errorCode()).isEqualTo(ErrorCode.UNSUPPORTED_PARAMETER);
                        assertThat(e.fieldErrors()).singleElement().extracting(f -> f.field()).isEqualTo("sort");
                    });
        }
        verifyNoInteractions(repository);
    }

    @Test
    void sortIsCaseInsensitive() {
        when(repository.listAll(any(), anyInt())).thenReturn(List.of());
        service.search(null, "NEWEST", 0, 20);
        service.search("", " Title ", 0, 20);
    }

    @Test
    void withoutTextTheDefaultIsNewestAndRelevanceMeansNewest() {
        var old = candidate("Old", 0, "O-1", Instant.parse("2026-01-01T00:00:00Z"));
        var recent = candidate("Recent", 0, "R-1", Instant.parse("2026-06-01T00:00:00Z"));
        when(repository.listAll(CatalogSearchRepository.Order.NEWEST, 500)).thenReturn(List.of(recent, old));
        everythingPublished();

        assertThat(service.search(null, null, 0, 20).items()).extracting(c -> c.title())
                .containsExactly("Recent", "Old");
        assertThat(service.search("   ", "relevance", 0, 20).items()).extracting(c -> c.title())
                .containsExactly("Recent", "Old");
        verify(repository, never()).match(any(), any(), anyInt());
        verifyNoInteractions(inventory);
    }

    @Test
    void titleSortWithoutTextAsksTheDatabaseForTitleOrder() {
        when(repository.listAll(CatalogSearchRepository.Order.TITLE, 500)).thenReturn(List.of());
        assertThat(service.search(null, "title", 0, 20).items()).isEmpty();
    }

    @Test
    void newestAndTitleSortsApplyToSearchResultsToo() {
        var a = candidate("Đẹp", 0.9, "A-1", Instant.parse("2026-01-01T00:00:00Z"));
        var b = candidate("Cốc", 0.1, "B-1", Instant.parse("2026-06-01T00:00:00Z"));
        var c = candidate("Bàn", 0.5, "C-1", null);
        when(repository.match(any(), any(), anyInt())).thenReturn(List.of(a, b, c));
        everythingPublished();

        assertThat(service.search("x", "newest", 0, 20).items()).extracting(i -> i.title())
                .containsExactly("Cốc", "Đẹp", "Bàn");
        // Folded: Bàn(ban) < Cốc(coc) < Đẹp(dep)
        assertThat(service.search("x", "title", 0, 20).items()).extracting(i -> i.title())
                .containsExactly("Bàn", "Cốc", "Đẹp");
        verifyNoInteractions(inventory);
    }

    // ---- text handling --------------------------------------------------------------------

    @Test
    void onlyPunctuationIsAnEmptyPageWithoutTouchingTheDatabase() {
        var page = service.search("!!! &|", null, 0, 20);
        assertThat(page.items()).isEmpty();
        assertThat(page.totalElements()).isZero();
        verifyNoInteractions(repository, products, inventory);
    }

    @Test
    void overLongTextIsRefusedAndNeverReachesTheDatabase() {
        assertThatThrownBy(() -> service.search("a".repeat(101), null, 0, 20))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED));
        verifyNoInteractions(repository);
    }

    @Test
    void theTypoFallbackRunsOnlyWhenTheExactSearchFindsNothing() {
        var typo = candidate("Ly nhua", 0.4, "L-1", null);
        when(repository.match(any(), any(), anyInt())).thenReturn(List.of());
        when(repository.similar(any(SearchQuery.class), any(), anyDouble(), anyInt())).thenReturn(List.of(typo));
        everythingPublished();
        when(inventory.availableQuantities(any())).thenReturn(Map.of());

        assertThat(service.search("ly nhuaa", null, 0, 20).items()).hasSize(1);

        when(repository.match(any(), any(), anyInt())).thenReturn(List.of(typo));
        service.search("ly nhua", null, 0, 20);
        verify(repository).similar(any(), any(), anyDouble(), anyInt()); // still once, from the first call
    }

    // ---- suggestions ----------------------------------------------------------------------

    @Test
    void suggestionsNeedTwoCharactersAndRefuseSillyLimits() {
        for (String q : new String[] {null, "", " ", "l", " l ", "!!", "-l-"}) {
            assertThatThrownBy(() -> service.suggest(q, 5)).as("q=%s", q)
                    .isInstanceOfSatisfying(BusinessException.class, e -> {
                        assertThat(e.errorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED);
                        assertThat(e.fieldErrors()).singleElement().extracting(f -> f.field()).isEqualTo("q");
                    });
        }
        for (int limit : new int[] {0, -1, 11}) {
            assertThatThrownBy(() -> service.suggest("ly", limit))
                    .isInstanceOfSatisfying(BusinessException.class,
                            e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.UNSUPPORTED_PARAMETER));
        }
        verifyNoInteractions(repository);
    }

    @Test
    void aSuggestionCarriesTheMatchedSkuElseTheFirstOne() {
        var byTitle = new Candidate(UUID.randomUUID(), "ly-nhua", "Ly nhua", null, 1, List.of("LY-1", "LY-2"),
                null, null);
        var bySku = new Candidate(UUID.randomUUID(), "coc", "Coc", null, 1, List.of("COC-1", "LY-9"),
                "LY-9", null);
        when(repository.suggest(any(), any(), anyInt())).thenReturn(List.of(byTitle, bySku));
        everythingPublished();

        assertThat(service.suggest("ly", 5)).extracting(s -> s.sku()).containsExactly("LY-1", "LY-9");
    }

    @Test
    void suggestionsAreCutAtTheLimitAfterTheLiveVisibilityCheck() {
        var list = IntStream.range(0, 6).mapToObj(i -> candidate("Ly " + i, 1, "L-" + i, null)).toList();
        when(repository.suggest(any(), any(), anyInt())).thenReturn(list);
        when(products.published(any())).thenReturn(
                Set.of(list.get(1).productId(), list.get(2).productId(), list.get(3).productId(),
                        list.get(4).productId()));

        assertThat(service.suggest("ly", 3)).extracting(s -> s.title()).containsExactly("Ly 1", "Ly 2", "Ly 3");
        verifyNoInteractions(inventory);
    }

    // ---- card fields ----------------------------------------------------------------------

    @Test
    void aPageAsksForItsCardDetailsOnceNotOncePerCard() {
        var rows = IntStream.range(0, 5).mapToObj(i -> candidate("Ly " + i, 1, "L-" + i, null)).toList();
        when(repository.listAll(any(), anyInt())).thenReturn(rows);
        everythingPublished();
        org.mockito.Mockito.doAnswer(i -> {
            Set<UUID> ids = i.getArgument(0);
            var map = new java.util.HashMap<UUID, com.stockflow.product.api.PublishedProductCard>();
            ids.forEach(id -> map.put(id, new com.stockflow.product.api.PublishedProductCard("https://img/" + id,
                    List.of(new com.stockflow.product.api.PublishedProductCard.Sku("S-" + id, "Name " + id)))));
            return map;
        }).when(products).cards(any());

        var page = service.search(null, null, 0, 20);

        assertThat(page.items()).hasSize(5).allSatisfy(c -> {
            assertThat(c.primaryImageUrl()).isEqualTo("https://img/" + c.productId());
            assertThat(c.skus()).containsExactly(
                    new com.stockflow.catalog.internal.service.CatalogCommerceService.CardSku("S-" + c.productId(), "Name " + c.productId()));
        });
        org.mockito.Mockito.verify(products, org.mockito.Mockito.times(1)).cards(any());
        org.mockito.Mockito.verify(products, org.mockito.Mockito.never()).publishedImages(any());
        org.mockito.Mockito.verify(products, org.mockito.Mockito.never()).read(any());
    }

    @Test
    void onlyTheRequestedPageIsLookedUp() {
        var rows = IntStream.range(0, 5).mapToObj(i -> candidate("Ly " + i, 1, "L-" + i, null)).toList();
        when(repository.listAll(any(), anyInt())).thenReturn(rows);
        everythingPublished();
        var asked = new java.util.ArrayList<Set<UUID>>();
        org.mockito.Mockito.doAnswer(i -> {
            Set<UUID> ids = i.getArgument(0);
            asked.add(ids);
            var map = new java.util.HashMap<UUID, com.stockflow.product.api.PublishedProductCard>();
            ids.forEach(id -> map.put(id, new com.stockflow.product.api.PublishedProductCard(null,
                    List.of(new com.stockflow.product.api.PublishedProductCard.Sku("S-" + id, "Name")))));
            return map;
        }).when(products).cards(any());

        var page = service.search(null, null, 1, 2);

        assertThat(page.items()).hasSize(2);
        assertThat(asked).hasSize(1);
        assertThat(asked.getFirst()).hasSize(2)
                .isEqualTo(page.items().stream().map(c -> c.productId()).collect(java.util.stream.Collectors.toSet()));
    }

    @Test
    void aRowWhoseProductWasUnpublishedMeanwhileIsDroppedNotShownWithoutSkus() {
        var gone = candidate("Ly gone", 1, "G-1", null);
        var live = candidate("Ly live", 1, "L-1", null);
        var unsellable = candidate("Ly none", 1, "N-1", null);
        when(repository.listAll(any(), anyInt())).thenReturn(List.of(gone, live, unsellable));
        everythingPublished();
        org.mockito.Mockito.doReturn(Map.of(
                live.productId(), new com.stockflow.product.api.PublishedProductCard("https://img/live",
                        List.of(new com.stockflow.product.api.PublishedProductCard.Sku("L-1", "Live"))),
                unsellable.productId(), new com.stockflow.product.api.PublishedProductCard(null, List.of())))
                .when(products).cards(any());

        var items = service.search(null, null, 0, 20).items();

        assertThat(items).extracting(c -> c.title()).containsExactly("Ly live");
    }
}
