package com.stockflow.product.internal.service;

import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.product.api.CategoryNavigation;
import com.stockflow.product.api.ProductService;
import com.stockflow.product.internal.service.ProductTaxonomyService.CategoryDetails;
import com.stockflow.product.internal.service.ProductTaxonomyService.CategoryView;
import com.stockflow.support.IntegrationTest;
import com.stockflow.support.PostgresContainer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * SCRUM-183/171 on a real database: an admin moves a category with its whole subtree — under another category or
 * to the root — and every path and depth follows, products keep their category ids, a move into its own subtree
 * or past the depth limit is refused, two crossing moves cannot make a cycle; the storefront reads the visible
 * tree and browses a category with its subcategories.
 */
@IntegrationTest
@AutoConfigureMockMvc
@Import(PostgresContainer.class)
class CategoryTreeIntegrationTest {

    @Autowired ProductTaxonomyService taxonomy;
    @Autowired CategoryNavigation navigation;
    @Autowired ProductService products;
    @Autowired JdbcTemplate jdbc;
    @Autowired MockMvc mvc;

    private String suffix;

    @BeforeEach
    void setUp() {
        suffix = UUID.randomUUID().toString().substring(0, 6).toUpperCase(Locale.ROOT);
    }

    private CategoryView create(String code, UUID parent) {
        return taxonomy.createCategory(code + "-" + suffix, parent, new CategoryDetails(code, 0, null, null, null, true));
    }

    private CategoryView reload(CategoryView category) {
        return taxonomy.category(category.id());
    }

    private static ErrorCode code(Throwable thrown) {
        return ((BusinessException) thrown).errorCode();
    }

    @Test
    @DisplayName("a subtree moves under another parent and back to the root; paths, depths and products follow")
    void moveSubtree() {
        CategoryView a = create("A", null);
        CategoryView b = create("B", a.id());
        CategoryView c = create("C", b.id());
        CategoryView d = create("D", null);
        var brand = taxonomy.createBrand("BR-" + suffix, "Brand", null);
        var product = products.create(new com.stockflow.product.api.CreateProductCommand("FX-" + suffix, "Fixture",
                "Fixture", brand.id(), c.id(), null, null, null, com.stockflow.product.api.TaxClass.STANDARD,
                com.stockflow.product.api.ProductKind.STANDARD, null));

        CategoryView moved = taxonomy.moveCategory(b.id(), d.id());

        assertThat(moved.parentId()).isEqualTo(d.id());
        assertThat(moved.path()).isEqualTo(d.path() + "/" + b.code());
        assertThat(moved.depth()).isEqualTo(1);
        assertThat(reload(c).path()).isEqualTo(d.path() + "/" + b.code() + "/" + c.code());
        assertThat(reload(c).depth()).isEqualTo(2);
        assertThat(reload(c).slug()).as("the slug never moves").isEqualTo(c.slug());
        assertThat(reload(a).path()).isEqualTo(a.path());

        CategoryView root = taxonomy.moveCategory(b.id(), null);
        assertThat(root.parentId()).isNull();
        assertThat(root.path()).isEqualTo("/" + b.code());
        assertThat(root.depth()).isZero();
        assertThat(reload(c).path()).isEqualTo("/" + b.code() + "/" + c.code());
        assertThat(reload(c).depth()).isEqualTo(1);
        assertThat(taxonomy.moveCategory(b.id(), null).version()).as("to where it is: nothing changes")
                .isEqualTo(root.version());

        assertThat(jdbc.queryForObject("SELECT category_id FROM product.product_categories WHERE product_id = ?",
                UUID.class, product.productId())).as("the product keeps its category id").isEqualTo(c.id());
    }

    @Test
    @DisplayName("a category cannot move under itself, under its own subtree, past the depth limit, or to nowhere")
    void refusals() {
        CategoryView a = create("A", null);
        CategoryView b = create("B", a.id());
        CategoryView c = create("C", b.id());
        assertThat(code(catchThrowable(() -> taxonomy.moveCategory(a.id(), a.id())))).isEqualTo(ErrorCode.VALIDATION_FAILED);
        assertThat(code(catchThrowable(() -> taxonomy.moveCategory(a.id(), c.id())))).isEqualTo(ErrorCode.VALIDATION_FAILED);
        assertThat(code(catchThrowable(() -> taxonomy.moveCategory(a.id(), UUID.randomUUID()))))
                .isEqualTo(ErrorCode.CATEGORY_NOT_FOUND);
        assertThat(code(catchThrowable(() -> taxonomy.moveCategory(UUID.randomUUID(), null))))
                .isEqualTo(ErrorCode.CATEGORY_NOT_FOUND);

        // A chain of 11 levels (depth 0..10) is full; a two-level subtree cannot go under its deepest node's parent.
        CategoryView deepest = create("L0", null);
        for (int level = 1; level <= 10; level++) {
            deepest = create("L" + level, deepest.id());
        }
        assertThat(deepest.depth()).isEqualTo(10);
        final UUID tenth = deepest.parentId();
        assertThat(code(catchThrowable(() -> taxonomy.moveCategory(b.id(), tenth)))).isEqualTo(ErrorCode.VALIDATION_FAILED);
        assertThat(reload(b).path()).as("nothing moved").isEqualTo(a.path() + "/" + b.code());
        CategoryView leaf = create("LEAF", null);
        assertThat(taxonomy.moveCategory(leaf.id(), tenth).depth()).isEqualTo(10);
    }

    @Test
    @DisplayName("two moves that would cross (A under B, B under A) run one after the other: one wins, no cycle")
    void crossingMovesCannotMakeACycle() throws Exception {
        CategoryView a = create("A", null);
        CategoryView b = create("B", null);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        List<Future<Boolean>> results = new ArrayList<>();
        try {
            for (UUID[] move : List.of(new UUID[] {a.id(), b.id()}, new UUID[] {b.id(), a.id()})) {
                results.add(pool.submit(() -> {
                    start.await();
                    try {
                        taxonomy.moveCategory(move[0], move[1]);
                        return true;
                    } catch (BusinessException refused) {
                        assertThat(refused.errorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED);
                        return false;
                    }
                }));
            }
            start.countDown();
            int moved = 0;
            for (Future<Boolean> result : results) {
                moved += result.get() ? 1 : 0;
            }
            assertThat(moved).isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
        CategoryView ra = reload(a);
        CategoryView rb = reload(b);
        assertThat(ra.parentId() == null || rb.parentId() == null).as("one of them is still a root").isTrue();
        for (CategoryView node : List.of(ra, rb)) {
            if (node.parentId() != null) {
                CategoryView parent = taxonomy.category(node.parentId());
                assertThat(node.path()).isEqualTo(parent.path() + "/" + node.code());
                assertThat(node.depth()).isEqualTo(parent.depth() + 1);
            }
        }
    }

    @Test
    @DisplayName("the storefront sees visible categories only, nested; a category browses its whole subtree")
    void storefrontNavigation() throws Exception {
        CategoryView a = create("NAV", null);
        CategoryView b = create("NAVB", a.id());
        CategoryView hidden = create("NAVH", a.id());
        CategoryView underHidden = create("NAVHC", hidden.id());
        taxonomy.updateCategory(hidden.id(), new CategoryDetails("Hidden", 0, null, null, null, false));

        List<UUID> visible = navigation.visibleTree().stream().map(CategoryNavigation.CategoryNode::id).toList();
        assertThat(visible).contains(a.id(), b.id()).doesNotContain(hidden.id(), underHidden.id());
        assertThat(navigation.visibleSubtree(a.slug())).hasValueSatisfying(ids ->
                assertThat(ids).containsExactlyInAnyOrder(a.id(), b.id()));
        assertThat(navigation.visibleSubtree(underHidden.slug())).as("under a hidden parent").isEmpty();
        assertThat(navigation.visibleSubtree("no-such-category")).isEmpty();

        mvc.perform(get("/api/v1/catalog/categories"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[?(@.id == '" + a.id() + "')].children[0].slug").value(b.slug()));
        mvc.perform(get("/api/v1/catalog/products").param("category", "no-such-category"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("CATEGORY_NOT_FOUND"));
        mvc.perform(get("/api/v1/catalog/products").param("category", b.slug()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items.length()").value(0));

        // The admin API moves it too, and the storefront follows at once.
        mvc.perform(put("/api/v1/categories/{id}/parent", b.id()).contentType("application/json")
                        .content("{\"parentId\":null}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.depth").value(0));
        assertThat(navigation.visibleSubtree(a.slug())).hasValueSatisfying(ids -> assertThat(ids).containsExactly(a.id()));
    }
}
